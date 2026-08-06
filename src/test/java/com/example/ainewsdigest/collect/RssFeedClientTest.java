package com.example.ainewsdigest.collect;

import com.example.ainewsdigest.support.Fixtures;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 피드를 호출하지 않는다. RSS 2.0과 Atom을 각각 픽스처로 고정한다. */
class RssFeedClientTest {

	private static final String RSS_PATH = "/openai/rss.xml";

	private static final String ATOM_PATH = "/simonwillison/atom.xml";

	private static final Instant SINCE = Instant.parse("2026-08-01T00:00:00Z");

	@RegisterExtension
	static final WireMockExtension wireMock = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	@Test
	void parsesBothRss20AndAtom() {
		stubRssFeed();
		stubAtomFeed();

		FetchResult result = client(feed("OpenAI", RSS_PATH), feed("Simon Willison", ATOM_PATH)).fetch(SINCE);

		assertFalse(result.failed());
		assertEquals(List.of("Introducing structured outputs for the Responses API",
						"Notes on running coding agents locally"),
				result.articles().stream().map(CandidateArticle::title).toList());
		CandidateArticle fromRss = result.articles().get(0);
		assertEquals("OpenAI", fromRss.sourceName());
		assertEquals("openai.com", fromRss.sourceDomain());
		assertEquals(Instant.parse("2026-08-05T09:00:00Z"), fromRss.publishedAt());
		// points 지표가 없는 소스다.
		assertEquals(0, fromRss.points());

		// Atom 항목은 <published>가 없어 <updated>로 대체된다.
		CandidateArticle fromAtom = result.articles().get(1);
		assertEquals("Simon Willison", fromAtom.sourceName());
		assertEquals("simonwillison.net", fromAtom.sourceDomain());
		assertEquals(Instant.parse("2026-08-05T12:00:00Z"), fromAtom.publishedAt());
	}

	@Test
	void excludesEntriesPublishedBeforeSince() {
		stubRssFeed();
		stubAtomFeed();

		FetchResult result = client(feed("OpenAI", RSS_PATH), feed("Simon Willison", ATOM_PATH)).fetch(SINCE);

		assertTrue(result.articles().stream().noneMatch(a -> a.url().contains("an-old-post")));
		assertTrue(result.articles().stream().noneMatch(a -> a.url().contains("an-old-entry")));
		assertEquals(2, result.articles().size());
	}

	/** 피드 하나가 죽어도 나머지는 계속 처리한다. 단 부분 실패도 실패다. */
	@Test
	void keepsOtherFeedsWhenOneFeedFailsAndStillReportsFailure() {
		wireMock.stubFor(get(RSS_PATH).willReturn(serverError()));
		stubAtomFeed();

		FetchResult result = client(feed("OpenAI", RSS_PATH), feed("Simon Willison", ATOM_PATH)).fetch(SINCE);

		assertTrue(result.failed());
		assertEquals(1, result.articles().size());
		assertEquals("Simon Willison", result.articles().get(0).sourceName());
	}

	@Test
	void allFeedsFailingIsReportedAsFailureWithoutThrowing() {
		wireMock.stubFor(get(RSS_PATH).willReturn(serverError()));
		wireMock.stubFor(get(ATOM_PATH).willReturn(aResponse().withStatus(503)));

		FetchResult result = client(feed("OpenAI", RSS_PATH), feed("Simon Willison", ATOM_PATH)).fetch(SINCE);

		assertTrue(result.failed());
		assertTrue(result.articles().isEmpty());
	}

	/** 성공한 빈 결과와 실패를 구분한다. */
	@Test
	void emptyButSuccessfulResultIsNotAFailure() {
		stubRssFeed();
		stubAtomFeed();

		FetchResult result = client(feed("OpenAI", RSS_PATH), feed("Simon Willison", ATOM_PATH))
				.fetch(Instant.parse("2027-01-01T00:00:00Z"));

		assertFalse(result.failed());
		assertTrue(result.articles().isEmpty());
	}

	/** 어댑터가 {@code CandidateArticle.of}를 쓰는지 확인한다. */
	@Test
	void alwaysPopulatesNormalizedFields() {
		stubRssFeed();
		stubAtomFeed();

		FetchResult result = client(feed("OpenAI", RSS_PATH), feed("Simon Willison", ATOM_PATH)).fetch(SINCE);

		assertFalse(result.articles().isEmpty());
		assertTrue(result.articles().stream()
				.allMatch(a -> !a.normalizedUrl().isBlank() && !a.sourceDomain().isBlank()));
		assertEquals("https://openai.com/index/structured-outputs-responses-api",
				result.articles().get(0).normalizedUrl());
	}

	private void stubRssFeed() {
		wireMock.stubFor(get(RSS_PATH).willReturn(aResponse()
				.withHeader("Content-Type", "application/rss+xml")
				.withBody(Fixtures.read("openai-rss.xml"))));
	}

	private void stubAtomFeed() {
		wireMock.stubFor(get(ATOM_PATH).willReturn(aResponse()
				.withHeader("Content-Type", "application/atom+xml")
				.withBody(Fixtures.read("simonwillison-atom.xml"))));
	}

	private static RssProperties.Feed feed(String name, String path) {
		return new RssProperties.Feed(name, wireMock.baseUrl() + path);
	}

	private static RssFeedClient client(RssProperties.Feed... feeds) {
		return new RssFeedClient(RestClient.builder(), ClientHttpRequestFactoryBuilder.detect(),
				HttpClientSettings.defaults(), new RssProperties(List.of(feeds)), new UrlNormalizer());
	}
}
