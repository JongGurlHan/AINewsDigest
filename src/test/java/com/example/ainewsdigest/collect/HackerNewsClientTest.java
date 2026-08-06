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
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 hn.algolia.com을 호출하지 않는다. 호출하면 CI가 네트워크·외부 서비스 상태에 종속된다. */
class HackerNewsClientTest {

	private static final String SEARCH_PATH = "/search_by_date";

	private static final Instant SINCE = Instant.parse("2026-08-05T00:00:00Z");

	@RegisterExtension
	static final WireMockExtension wireMock = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	/** 같은 기사가 다른 장식(후행 슬래시)으로 들어오고, 새 기사가 하나 더 있는 두 번째 키워드 응답. */
	private static final String SECOND_KEYWORD_RESPONSE = """
			{
			  "hits": [
			    {
			      "title": "Claude Code 2.1 ships subagents",
			      "url": "https://anthropic.com/news/claude-code-2-1/",
			      "points": 400,
			      "created_at": "2026-08-05T22:10:00.000Z",
			      "objectID": "44100011"
			    },
			    {
			      "title": "OpenAI ships a new coding model",
			      "url": "https://openai.com/index/new-coding-model/",
			      "points": 310,
			      "created_at": "2026-08-05T17:00:00.000Z",
			      "objectID": "44100012"
			    }
			  ],
			  "nbHits": 2
			}
			""";

	@Test
	void convertsHitsIntoCandidatesWithNormalizedFields() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(okJson(Fixtures.read("hn-search.json"))));

		FetchResult result = client("claude code").fetch(SINCE);

		assertEquals(List.of("Claude Code 2.1 ships subagents", "Cursor 2.0 changelog"),
				result.articles().stream().map(CandidateArticle::title).toList());
		CandidateArticle article = result.articles().get(0);
		assertEquals("https://www.anthropic.com/news/claude-code-2-1?utm_source=hn", article.url());
		assertEquals("https://anthropic.com/news/claude-code-2-1", article.normalizedUrl());
		// sourceDomain은 원문 URL에서 뽑는다. news.ycombinator.com이 아니다.
		assertEquals("anthropic.com", article.sourceDomain());
		assertEquals("Hacker News", article.sourceName());
		assertEquals(412, article.points());
		assertEquals(Instant.parse("2026-08-05T22:10:00Z"), article.publishedAt());
	}

	/** Ask HN 같은 자체 글은 외부 원문이 없어 본문 크롤링(step 3)이 불가능하다. */
	@Test
	void dropsHitsWithoutUrl() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(okJson(Fixtures.read("hn-search.json"))));

		FetchResult result = client("claude code").fetch(SINCE);

		assertTrue(result.articles().stream().noneMatch(a -> a.title().startsWith("Ask HN")));
	}

	/** {@code javascript:} URL은 step 10의 {@code <a th:href>}로 렌더링된다. 진입점에서 거른다. */
	@Test
	void dropsNonHttpSchemeHits() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(okJson(Fixtures.read("hn-search.json"))));

		FetchResult result = client("claude code").fetch(SINCE);

		assertTrue(result.articles().stream().noneMatch(a -> a.url().startsWith("javascript:")));
		// 정상 hit은 그대로 수집된다.
		assertTrue(result.articles().stream().anyMatch(a -> a.sourceDomain().equals("cursor.com")));
	}

	/** {@code digest_item.source_domain}이 varchar(100)이다. 넘치면 step 6 저장에서 하루치가 날아간다. */
	@Test
	void dropsHitsWithOversizedSourceDomain() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(okJson(Fixtures.read("hn-search.json"))));

		FetchResult result = client("claude code").fetch(SINCE);

		assertTrue(result.articles().stream().allMatch(a -> a.sourceDomain().length() <= 100));
	}

	/** 값 검증 탈락은 소스 장애가 아니다. failed를 세우면 관리자에게 오탐 알림이 간다. */
	@Test
	void rejectedHitsDoNotMarkTheSourceAsFailed() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(okJson(Fixtures.read("hn-search.json"))));

		FetchResult result = client("claude code").fetch(SINCE);

		assertFalse(result.failed());
		assertEquals(2, result.articles().size());
	}

	@Test
	void callsOncePerKeywordAndMergesDuplicatesByNormalizedUrl() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).withQueryParam("query", equalTo("claude code"))
				.willReturn(okJson(Fixtures.read("hn-search.json"))));
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).withQueryParam("query", equalTo("cursor"))
				.willReturn(okJson(SECOND_KEYWORD_RESPONSE)));

		FetchResult result = client("claude code", "cursor").fetch(SINCE);

		wireMock.verify(2, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
		// 같은 기사가 양쪽에 나왔으므로 3건이다 (anthropic 1건 + cursor + openai).
		assertEquals(List.of("anthropic.com", "cursor.com", "openai.com"),
				result.articles().stream().map(CandidateArticle::sourceDomain).sorted().toList());
		assertFalse(result.failed());
	}

	@Test
	void sendsConfiguredFiltersOnEveryRequest() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(okJson(Fixtures.read("hn-search.json"))));

		client("claude code").fetch(SINCE);

		wireMock.verify(getRequestedFor(urlPathEqualTo(SEARCH_PATH))
				.withQueryParam("tags", equalTo("story"))
				.withQueryParam("hitsPerPage", equalTo("40"))
				.withQueryParam("numericFilters",
						matching("created_at_i.*" + SINCE.getEpochSecond() + ".*points.*30")));
	}

	/** 성공한 빈 결과와 실패를 구분한다. 이 구분이 없으면 수집 전면 장애가 "뉴스 없는 날"로 위장된다. */
	@Test
	void emptyButSuccessfulResponseIsNotAFailure() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(okJson("{\"hits\": [], \"nbHits\": 0}")));

		FetchResult result = client("claude code").fetch(SINCE);

		assertFalse(result.failed());
		assertTrue(result.articles().isEmpty());
	}

	@Test
	void serverErrorIsReportedAsFailureWithoutThrowing() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(serverError()));

		FetchResult result = client("claude code").fetch(SINCE);

		assertTrue(result.failed());
		assertTrue(result.articles().isEmpty());
	}

	/** 타임아웃과 같은 I/O 실패 경로. 읽기 타임아웃(10초)을 실제로 기다리지 않기 위해 연결을 끊는다. */
	@Test
	void ioFailureIsReportedAsFailureWithoutThrowing() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
				.willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

		FetchResult result = client("claude code").fetch(SINCE);

		assertTrue(result.failed());
		assertTrue(result.articles().isEmpty());
	}

	/** 키워드 하나가 죽어도 나머지 결과는 살린다. 단 부분 실패도 실패로 표시한다. */
	@Test
	void keepsResultsOfSurvivingKeywordAndStillReportsFailure() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).withQueryParam("query", equalTo("claude code"))
				.willReturn(okJson(Fixtures.read("hn-search.json"))));
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).withQueryParam("query", equalTo("cursor"))
				.willReturn(serverError()));

		FetchResult result = client("claude code", "cursor").fetch(SINCE);

		assertTrue(result.failed());
		assertEquals(2, result.articles().size());
	}

	@Test
	void exposesSourceName() {
		assertEquals("Hacker News", client("claude code").name());
	}

	private static HackerNewsClient client(String... keywords) {
		HackerNewsProperties properties =
				new HackerNewsProperties(wireMock.baseUrl(), 30, 40, List.of(keywords));
		return new HackerNewsClient(RestClient.builder(), ClientHttpRequestFactoryBuilder.detect(),
				HttpClientSettings.defaults(), properties, new UrlNormalizer());
	}

	/** 어댑터가 {@code CandidateArticle.of}를 쓰는지 확인한다 — 정규화 필드가 비면 중복 제거가 몰살한다. */
	@Test
	void alwaysPopulatesNormalizedFields() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(okJson(Fixtures.read("hn-search.json"))));

		FetchResult result = client("claude code").fetch(SINCE);

		assertFalse(result.articles().isEmpty());
		assertTrue(result.articles().stream()
				.allMatch(a -> !a.normalizedUrl().isBlank() && !a.sourceDomain().isBlank()));
	}
}
