package com.example.ainewsdigest.collect;

import com.example.ainewsdigest.support.Fixtures;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 raw.githubusercontent.com을 호출하지 않는다. CHANGELOG 본문은 픽스처로 고정한다. */
class ChangelogClientTest {

	private static final String CHANGELOG_PATH = "/anthropics/claude-code/main/CHANGELOG.md";

	private static final Instant SINCE = Instant.parse("2026-08-05T00:00:00Z");

	@RegisterExtension
	static final WireMockExtension wireMock = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	/** 다음 릴리즈가 나간 뒤의 같은 파일. 최상단 버전만 바뀐다. */
	private static final String NEXT_RELEASE = """
			# Changelog

			## 2.1.223

			- Fixed a regression in `--print`

			## 2.1.222

			- Fixed a bug where `/compact` could drop the last message
			""";

	@Test
	void takesOnlyTheLatestVersionSection() {
		stubChangelog(Fixtures.read("claude-code-changelog.md"));

		FetchResult result = client().fetch(SINCE);

		assertFalse(result.failed());
		assertEquals(1, result.articles().size());
		CandidateArticle article = result.articles().get(0);
		assertEquals("Claude Code 2.1.222 릴리즈", article.title());
		assertEquals("Claude Code", article.sourceName());
		// 인기 지표가 없는 소스다.
		assertEquals(0, article.points());
	}

	/**
	 * 버전은 <b>쿼리 파라미터</b>여야 한다. {@code UrlNormalizer}가 fragment를 제거하므로
	 * 앵커를 쓰면 모든 버전이 같은 URL로 정규화된다.
	 */
	@Test
	void appendsVersionAsQueryParameter() {
		stubChangelog(Fixtures.read("claude-code-changelog.md"));

		CandidateArticle article = client().fetch(SINCE).articles().get(0);

		assertEquals(wireMock.baseUrl() + CHANGELOG_PATH + "?v=2.1.222", article.url());
	}

	/**
	 * 버전이 다르면 정규화 후에도 URL이 달라야 한다. 같아지면 최초 1회 발송 뒤 모든 릴리즈가
	 * step 6의 {@code normalized_url} 중복 제거에 영원히 걸린다.
	 */
	@Test
	void differentVersionsProduceDifferentNormalizedUrls() {
		stubChangelog(Fixtures.read("claude-code-changelog.md"));
		String previous = client().fetch(SINCE).articles().get(0).normalizedUrl();

		wireMock.resetAll();
		stubChangelog(NEXT_RELEASE);
		String next = client().fetch(SINCE).articles().get(0).normalizedUrl();

		assertNotEquals(previous, next);
		assertTrue(previous.endsWith("?v=2.1.222"), previous);
		assertTrue(next.endsWith("?v=2.1.223"), next);
	}

	/**
	 * 후보 URL은 raw 마크다운({@code text/plain})이라 step 3의 크롤링이 <b>절대</b> 본문을 얻지 못한다
	 * ({@code JsoupArticleExtractor}는 HTML만 받는다). 그래서 이미 파싱해 둔 버전 섹션을 후보에 실어 보낸다.
	 */
	@Test
	void carriesTheVersionSectionAsContent() {
		stubChangelog(Fixtures.read("claude-code-changelog.md"));

		CandidateArticle article = client().fetch(SINCE).articles().get(0);

		assertTrue(article.hasContent());
		assertTrue(article.content().contains("/compact"), article.content());
		assertTrue(article.content().contains("Improved subagent tool permission prompts"), article.content());
	}

	/** 다음 버전 섹션까지 딸려 오면 안 된다. 2.1.221 내용으로 요약된 "2.1.222 릴리즈"가 나간다. */
	@Test
	void contentStopsAtTheNextVersionHeading() {
		stubChangelog(Fixtures.read("claude-code-changelog.md"));

		CandidateArticle article = client().fetch(SINCE).articles().get(0);

		assertFalse(article.content().contains("MCP server reconnection"), article.content());
		assertFalse(article.content().contains("2.1.221"), article.content());
	}

	/**
	 * 릴리즈 노트가 길어도 요약 프롬프트에 통째로 실리면 안 된다. 크롤링 경로의
	 * {@code max-content-length}와 같은 예산(3,000자)을 쓴다.
	 */
	@Test
	void truncatesAnOverlongSection() {
		String body = ("- " + "변경".repeat(60) + "\n").repeat(100);
		stubChangelog("# Changelog\n\n## 9.9.9\n\n" + body + "\n## 9.9.8\n\n- 이전 릴리즈\n");

		String content = client().fetch(SINCE).articles().get(0).content();

		assertEquals(3000, content.codePointCount(0, content.length()));
	}

	/**
	 * 헤딩만 있고 변경 목록이 비어 있는 날. 없는 본문을 억지로 실어 보내면 LLM이 릴리즈 내용을 지어낸다.
	 * 본문 없이 내보내 크롤링 경로로 보내고, 거기서 탈락하는 편이 낫다.
	 */
	@Test
	void leavesContentEmptyWhenTheSectionHasNoBody() {
		stubChangelog("""
				# Changelog

				## 2.1.223

				## 2.1.222

				- Fixed a bug where `/compact` could drop the last message
				""");

		CandidateArticle article = client().fetch(SINCE).articles().get(0);

		assertEquals("Claude Code 2.1.223 릴리즈", article.title());
		assertFalse(article.hasContent());
	}

	/** 어댑터가 {@code CandidateArticle.of}를 쓰는지 확인한다 — 정규화 필드가 비면 중복 제거가 몰살한다. */
	@Test
	void alwaysPopulatesNormalizedFields() {
		stubChangelog(Fixtures.read("claude-code-changelog.md"));

		CandidateArticle article = client().fetch(SINCE).articles().get(0);

		assertFalse(article.normalizedUrl().isBlank());
		assertFalse(article.sourceDomain().isBlank());
		assertEquals("localhost", article.sourceDomain());
	}

	/** 파일에 게시 날짜가 없다. {@code since} 필터를 적용하면 이 소스는 아무것도 내놓지 못한다. */
	@Test
	void doesNotApplyTheSinceFilter() {
		stubChangelog(Fixtures.read("claude-code-changelog.md"));

		FetchResult result = client().fetch(Instant.parse("2027-01-01T00:00:00Z"));

		assertEquals(1, result.articles().size());
	}

	/**
	 * 형식이 바뀌었다는 뜻이다. 조용히 빈 결과로 넘어가면 이 소스가 죽은 채로 방치된다 —
	 * 후보 0건은 "오늘은 조용한 날"로 읽히기 때문이다.
	 */
	@Test
	void unparseableVersionIsReportedAsFailure() {
		stubChangelog("""
				# Changelog

				릴리즈 노트는 문서 사이트로 옮겼습니다.
				""");

		FetchResult result = client().fetch(SINCE);

		assertTrue(result.failed());
		assertTrue(result.articles().isEmpty());
	}

	/** {@code ## Unreleased}를 버전으로 오인하면 매일 같은 후보가 나간다. */
	@Test
	void skipsHeadingsThatAreNotVersions() {
		stubChangelog("""
				# Changelog

				## Unreleased

				- 아직 배포되지 않은 변경

				## 2.1.222

				- Fixed a bug where `/compact` could drop the last message
				""");

		CandidateArticle article = client().fetch(SINCE).articles().get(0);

		assertEquals("Claude Code 2.1.222 릴리즈", article.title());
	}

	@Test
	void serverErrorIsReportedAsFailureWithoutThrowing() {
		wireMock.stubFor(get(CHANGELOG_PATH).willReturn(serverError()));

		FetchResult result = client().fetch(SINCE);

		assertTrue(result.failed());
		assertTrue(result.articles().isEmpty());
	}

	/** 타임아웃과 같은 I/O 실패 경로. 읽기 타임아웃(10초)을 실제로 기다리지 않기 위해 연결을 끊는다. */
	@Test
	void ioFailureIsReportedAsFailureWithoutThrowing() {
		wireMock.stubFor(get(CHANGELOG_PATH)
				.willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

		FetchResult result = client().fetch(SINCE);

		assertTrue(result.failed());
		assertTrue(result.articles().isEmpty());
	}

	@Test
	void exposesSourceName() {
		assertEquals("Claude Code CHANGELOG", client().name());
	}

	private void stubChangelog(String markdown) {
		wireMock.stubFor(get(CHANGELOG_PATH).willReturn(aResponse()
				.withHeader("Content-Type", "text/plain; charset=utf-8")
				.withBody(markdown)));
	}

	private static ChangelogClient client() {
		ChangelogProperties properties = new ChangelogProperties(wireMock.baseUrl() + CHANGELOG_PATH);
		return new ChangelogClient(RestClient.builder(), ClientHttpRequestFactoryBuilder.detect(),
				HttpClientSettings.defaults(), properties, new UrlNormalizer());
	}
}
