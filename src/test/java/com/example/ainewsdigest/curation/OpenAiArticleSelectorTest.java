package com.example.ainewsdigest.curation;

import com.example.ainewsdigest.collect.CandidateArticle;
import com.example.ainewsdigest.collect.UrlNormalizer;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OpenAI 응답은 전부 WireMock으로 스텁한다. 실제 API를 부르지 않는다. */
class OpenAiArticleSelectorTest {

	private static final String CHAT_PATH = "/v1/chat/completions";

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final UrlNormalizer NORMALIZER = new UrlNormalizer();

	/** 평문 h2c 업그레이드가 POST 본문을 유실시킨다. 스텁 서버를 HTTP/1.1로 고정한다. */
	@RegisterExtension
	static final WireMockExtension wireMock = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort().http2PlainDisabled(true))
			.build();

	private static final List<CandidateArticle> CANDIDATES = List.of(
			candidate("Cursor 2.0 released", "https://cursor.com/blog/2-0", 320),
			candidate("Anthropic ships Claude Code hooks", "https://www.anthropic.com/news/hooks", 210),
			candidate("Why I moved my blog to a static site", "https://example.com/blog/static", 45));

	@Test
	void mapsResponseToScoredArticlesRankedByScore() {
		stubResults("""
				{"results":[
				  {"index":0,"score":4,"reason":"에디터 메이저 릴리즈"},
				  {"index":1,"score":5,"reason":"매일 쓰는 도구의 동작이 바뀐다"},
				  {"index":2,"score":1,"reason":"개발자용 AI 소식이 아니다"}
				]}
				""");

		List<ScoredArticle> scored = selector().scoreAndRank(CANDIDATES, List.of());

		assertEquals(3, scored.size());
		assertEquals(List.of(5, 4, 1), scored.stream().map(ScoredArticle::score).toList());
		assertEquals("Anthropic ships Claude Code hooks", scored.get(0).article().title());
		assertEquals("매일 쓰는 도구의 동작이 바뀐다", scored.get(0).reason());
		assertEquals("Cursor 2.0 released", scored.get(1).article().title());
	}

	/** 동점이면 입력 순서(수집 순서)를 유지한다. 순서가 매번 흔들리면 결과를 재현할 수 없다. */
	@Test
	void keepsInputOrderForTiedScores() {
		stubResults("""
				{"results":[
				  {"index":0,"score":3,"reason":"a"},
				  {"index":1,"score":3,"reason":"b"},
				  {"index":2,"score":3,"reason":"c"}
				]}
				""");

		List<ScoredArticle> scored = selector().scoreAndRank(CANDIDATES, List.of());

		assertEquals(List.of("Cursor 2.0 released", "Anthropic ships Claude Code hooks",
						"Why I moved my blog to a static site"),
				scored.stream().map(article -> article.article().title()).toList());
	}

	@Test
	void forcesResponseStructureWithJsonSchema() {
		stubResults("{\"results\":[]}");

		selector().scoreAndRank(CANDIDATES, List.of());

		Map<String, Object> responseFormat = section(lastRequestBody(), "response_format");
		assertEquals("json_schema", responseFormat.get("type"));
		assertEquals(Boolean.TRUE, section(responseFormat, "json_schema").get("strict"));
	}

	/** 최근에 보낸 제목을 넘기지 않으면 같은 사건이 며칠 연속으로 발송된다. */
	@Test
	void sendsRecentTitlesInThePrompt() {
		stubResults("{\"results\":[]}");

		selector().scoreAndRank(CANDIDATES, List.of("Cursor 2.0 출시", "Claude Code 훅 추가"));

		String userPrompt = userPrompt(lastRequestBody());
		assertTrue(userPrompt.contains("Cursor 2.0 출시"), userPrompt);
		assertTrue(userPrompt.contains("Claude Code 훅 추가"), userPrompt);
	}

	@Test
	void putsScoringCriteriaInTheSystemPrompt() {
		stubResults("{\"results\":[]}");

		selector().scoreAndRank(CANDIDATES, List.of());

		String systemPrompt = systemPrompt(lastRequestBody());
		assertTrue(systemPrompt.contains("Claude Code"), systemPrompt);
		assertTrue(systemPrompt.contains("5점"), systemPrompt);
		assertTrue(systemPrompt.contains("1점"), systemPrompt);
	}

	/** 후보의 제목·출처·points만 넘긴다. 본문은 아직 크롤링하지 않았다 (ADR-007). */
	@Test
	void sendsTitleSourceAndPointsForEachCandidate() {
		stubResults("{\"results\":[]}");

		selector().scoreAndRank(CANDIDATES, List.of());

		String userPrompt = userPrompt(lastRequestBody());
		assertTrue(userPrompt.contains("Cursor 2.0 released"), userPrompt);
		assertTrue(userPrompt.contains("cursor.com"), userPrompt);
		assertTrue(userPrompt.contains("320"), userPrompt);
	}

	/** 응답 순서를 신뢰하지 않는다. 엉뚱한 기사에 점수가 붙으면 그대로 발송된다. */
	@Test
	void matchesByIndexNotByResponseOrder() {
		stubResults("""
				{"results":[
				  {"index":2,"score":2,"reason":"c"},
				  {"index":0,"score":5,"reason":"a"}
				]}
				""");

		List<ScoredArticle> scored = selector().scoreAndRank(CANDIDATES, List.of());

		assertEquals(2, scored.size());
		assertEquals("Cursor 2.0 released", scored.get(0).article().title());
		assertEquals(5, scored.get(0).score());
		assertEquals("Why I moved my blog to a static site", scored.get(1).article().title());
	}

	@Test
	void ignoresOutOfRangeIndexes() {
		stubResults("""
				{"results":[
				  {"index":7,"score":5,"reason":"없는 후보"},
				  {"index":-1,"score":5,"reason":"없는 후보"},
				  {"index":0,"score":3,"reason":"a"}
				]}
				""");

		List<ScoredArticle> scored = selector().scoreAndRank(CANDIDATES, List.of());

		assertEquals(1, scored.size());
		assertEquals("Cursor 2.0 released", scored.get(0).article().title());
	}

	/** JSON Schema의 strict 모드는 숫자 범위를 강제하지 못한다. 범위 밖 점수는 코드가 맞춘다. */
	@Test
	void clampsScoresIntoTheOneToFiveRange() {
		stubResults("""
				{"results":[
				  {"index":0,"score":9,"reason":"a"},
				  {"index":1,"score":0,"reason":"b"}
				]}
				""");

		List<ScoredArticle> scored = selector().scoreAndRank(CANDIDATES, List.of());

		assertEquals(5, scored.get(0).score());
		assertEquals(1, scored.get(1).score());
	}

	/** 후보가 없으면 호출할 이유가 없다. 돈만 쓴다. */
	@Test
	void doesNotCallTheApiWhenThereAreNoCandidates() {
		assertEquals(List.of(), selector().scoreAndRank(List.of(), List.of("최근 제목")));

		wireMock.verify(0, postRequestedFor(urlEqualTo(CHAT_PATH)));
	}

	/**
	 * 선별 실패는 부분 실패가 아니다. 채점이 없으면 그날 다이제스트가 성립하지 않으므로
	 * 빈 리스트가 아니라 예외로 올려보낸다 (ADR-016).
	 */
	@Test
	void throwsWhenTheApiKeepsFailing() {
		wireMock.stubFor(post(urlEqualTo(CHAT_PATH)).willReturn(serverError()));

		assertThrows(CurationException.class, () -> selector().scoreAndRank(CANDIDATES, List.of()));
	}

	private static CandidateArticle candidate(String title, String url, int points) {
		return CandidateArticle.of(title, url, "Hacker News", points,
				Instant.parse("2026-08-06T00:00:00Z"), NORMALIZER).orElseThrow();
	}

	private static void stubResults(String content) {
		wireMock.stubFor(post(urlEqualTo(CHAT_PATH))
				.willReturn(okJson(OpenAiClientTest.chatResponse(content))));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> lastRequestBody() {
		List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlEqualTo(CHAT_PATH)));
		return MAPPER.readValue(requests.get(requests.size() - 1).getBodyAsString(), Map.class);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> section(Map<String, Object> body, String key) {
		return (Map<String, Object>) body.get(key);
	}

	static String systemPrompt(Map<String, Object> body) {
		return messageContent(body, 0);
	}

	static String userPrompt(Map<String, Object> body) {
		return messageContent(body, 1);
	}

	@SuppressWarnings("unchecked")
	private static String messageContent(Map<String, Object> body, int position) {
		List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
		return (String) messages.get(position).get("content");
	}

	private static OpenAiArticleSelector selector() {
		OpenAiProperties properties = new OpenAiProperties(wireMock.baseUrl() + "/v1", "test-key", "gpt-5-mini",
				Duration.ofSeconds(5), 2, Duration.ofMillis(10));
		OpenAiClient client = new OpenAiClient(RestClient.builder(), ClientHttpRequestFactoryBuilder.detect(),
				HttpClientSettings.defaults(), properties);
		return new OpenAiArticleSelector(client, MAPPER);
	}
}
