package com.example.ainewsdigest.collect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ainewsdigest.support.Fixtures;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 뉴스 사이트를 크롤링하지 않는다. 파싱은 픽스처로, 네트워크 경로는 WireMock으로 검증한다.
 *
 * <p>WireMock은 {@code localhost}에 뜨는데 그건 loopback이라 {@link SafeUrlPolicy}가 정상적으로 막는다.
 * 그래서 테스트용 {@link SafeUrlPolicy.HostResolver}가 {@code localhost}만 공인 IP로 취급한다.
 * IP 리터럴({@code 127.0.0.1}, {@code 169.254.169.254})은 그대로 해석되므로 SSRF 검증은 살아 있다.
 */
class JsoupArticleExtractorTest {

	private static final String ARTICLE_PATH = "/article";

	private static final String METADATA_PATH = "/opc/v1/instance/";

	private static final String BASE_URI = "https://example.com/article";

	/** 공인 IP 리터럴. 해석에 DNS가 필요 없다. */
	private static final String PUBLIC_IP = "93.184.216.34";

	private static final SafeUrlPolicy.HostResolver TEST_RESOLVER =
			host -> InetAddress.getAllByName("localhost".equalsIgnoreCase(host) ? PUBLIC_IP : host);

	@RegisterExtension
	static final WireMockExtension wireMock = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	@BeforeEach
	void captureLogs() {
		logs.start();
		logger().addAppender(logs);
	}

	@AfterEach
	void releaseLogs() {
		logger().detachAppender(logs);
		logs.stop();
	}

	// --- 파싱 (네트워크 없음) ---------------------------------------------------------------

	@Test
	void extractsBodyFromAnArticleElement() {
		Optional<String> text = extractor().extractFromHtml(Fixtures.read("article-semantic.html"), BASE_URI);

		assertTrue(text.isPresent());
		assertTrue(text.get().startsWith("Anthropic released Claude Code 2.2 today"), text.get());
		assertTrue(text.get().contains("Pricing is unchanged."), text.get());
	}

	@Test
	void extractsBodyFromAMainElementWhenThereIsNoArticle() {
		Optional<String> text = extractor().extractFromHtml(Fixtures.read("article-main-only.html"), BASE_URI);

		assertTrue(text.isPresent());
		assertTrue(text.get().startsWith("Cursor 2.4 replaces the inline diff view"), text.get());
		assertTrue(text.get().contains("Indexing was rewritten"), text.get());
	}

	/** 시맨틱 태그가 없으면 {@code <p>} 텍스트가 가장 많은 블록을 고른다 — 사이드바·댓글이 아니라 본문이다. */
	@Test
	void fallsBackToTheBlockWithTheMostParagraphText() {
		Optional<String> text = extractor().extractFromHtml(Fixtures.read("article-no-semantic.html"), BASE_URI);

		assertTrue(text.isPresent());
		assertTrue(text.get().startsWith("OpenAI published a smaller reasoning model"), text.get());
		assertFalse(text.get().contains("SIDEBAR-BLOCK-TEXT"), text.get());
		assertFalse(text.get().contains("COMMENTS-BLOCK-TEXT"), text.get());
	}

	/** 이 텍스트가 섞이면 LLM이 네비게이션 메뉴를 뉴스로 요약한다. */
	@Test
	void dropsScriptNavAndFooterText() {
		String text = extractor().extractFromHtml(Fixtures.read("article-semantic.html"), BASE_URI).orElseThrow();

		for (String noise : List.of("SCRIPT-BLOCK-TEXT", "STYLE-BLOCK-TEXT", "NAV-BLOCK-TEXT",
				"HEADER-BLOCK-TEXT", "FOOTER-BLOCK-TEXT", "ASIDE-BLOCK-TEXT", "FORM-BLOCK-TEXT",
				"NOSCRIPT-BLOCK-TEXT")) {
			assertFalse(text.contains(noise), noise + " 가 본문에 섞였다: " + text);
		}
	}

	@Test
	void truncatesContentLongerThanTheLimit() {
		String paragraph = "<p>" + "가".repeat(500) + "</p>";
		String html = "<html><body><article>" + paragraph.repeat(10) + "</article></body></html>";

		String text = extractor().extractFromHtml(html, BASE_URI).orElseThrow();

		assertEquals(3000, text.length());
	}

	/**
	 * 쿠키 배너나 페이월 안내문만 긁힌 경우다. 이런 텍스트로 요약하면 엉뚱한 내용이 발송된다.
	 */
	@Test
	void treatsTooShortContentAsAFailure() {
		Optional<String> text = extractor().extractFromHtml(Fixtures.read("article-cookie-banner.html"), BASE_URI);

		assertTrue(text.isEmpty());
	}

	// --- 네트워크 (WireMock) ----------------------------------------------------------------

	@Test
	void extractsOverHttp() {
		stubArticle(ARTICLE_PATH);

		Optional<String> text = extractor().extract(wireMock.baseUrl() + ARTICLE_PATH);

		assertTrue(text.isPresent());
		assertTrue(text.get().startsWith("Anthropic released Claude Code 2.2 today"), text.get());
	}

	@Test
	void returnsEmptyOnNotFound() {
		wireMock.stubFor(get(ARTICLE_PATH).willReturn(notFound()));

		assertTrue(extractor().extract(wireMock.baseUrl() + ARTICLE_PATH).isEmpty());
	}

	@Test
	void returnsEmptyWhenTheResponseIsNotHtml() {
		wireMock.stubFor(get(ARTICLE_PATH).willReturn(aResponse()
				.withHeader("Content-Type", "application/pdf")
				.withBody("%PDF-1.7")));

		assertTrue(extractor().extract(wireMock.baseUrl() + ARTICLE_PATH).isEmpty());
	}

	// --- SSRF (ADR-017) --------------------------------------------------------------------

	/**
	 * 최초 URL만 검사하는 것은 검사하지 않는 것과 같다. {@code https://정상사이트/r} 이
	 * {@code http://169.254.169.254/} 로 302 하는 순간 전부 무너진다.
	 */
	@Test
	void doesNotFollowARedirectToTheMetadataAddress() {
		wireMock.stubFor(get("/r").willReturn(aResponse()
				.withStatus(302)
				.withHeader("Location", "http://169.254.169.254" + METADATA_PATH)));

		assertTrue(extractor().extract(wireMock.baseUrl() + "/r").isEmpty());
	}

	/**
	 * 리다이렉트 대상을 WireMock 자신(loopback)으로 두면 "요청이 나가지 않았다"를 실제로 확인할 수 있다.
	 * 정책이 없으면 이 요청은 WireMock에 도달한다.
	 */
	@Test
	void doesNotSendTheRequestWhenARedirectTargetIsInternal() {
		wireMock.stubFor(get("/r").willReturn(aResponse()
				.withStatus(302)
				.withHeader("Location", "http://127.0.0.1:" + wireMock.getPort() + METADATA_PATH)));
		stubArticle(METADATA_PATH);

		Optional<String> text = extractor().extract(wireMock.baseUrl() + "/r");

		assertTrue(text.isEmpty());
		wireMock.verify(0, getRequestedFor(urlEqualTo(METADATA_PATH)));
	}

	@Test
	void followsARedirectBetweenAllowedUrls() {
		wireMock.stubFor(get("/r").willReturn(aResponse()
				.withStatus(302)
				.withHeader("Location", wireMock.baseUrl() + ARTICLE_PATH)));
		stubArticle(ARTICLE_PATH);

		Optional<String> text = extractor().extract(wireMock.baseUrl() + "/r");

		assertTrue(text.isPresent());
		assertTrue(text.get().startsWith("Anthropic released Claude Code 2.2 today"), text.get());
		wireMock.verify(1, getRequestedFor(urlEqualTo(ARTICLE_PATH)));
	}

	@Test
	void givesUpWhenThereAreMoreRedirectsThanAllowed() {
		for (int hop = 1; hop <= 4; hop++) {
			wireMock.stubFor(get("/r" + hop).willReturn(aResponse()
					.withStatus(302)
					.withHeader("Location", wireMock.baseUrl() + (hop < 4 ? "/r" + (hop + 1) : ARTICLE_PATH))));
		}
		stubArticle(ARTICLE_PATH);

		assertTrue(extractor().extract(wireMock.baseUrl() + "/r1").isEmpty());
		wireMock.verify(0, getRequestedFor(urlEqualTo(ARTICLE_PATH)));
	}

	/** 정책 위반은 조사 대상이고 페이월·404는 정상 흐름이다. 로그에서 구분되지 않으면 조사할 수 없다. */
	@Test
	void logsPolicyViolationsAndOrdinaryFailuresDifferently() {
		wireMock.stubFor(get("/r").willReturn(aResponse()
				.withStatus(302)
				.withHeader("Location", "http://169.254.169.254" + METADATA_PATH)));
		wireMock.stubFor(get(ARTICLE_PATH).willReturn(notFound()));

		extractor().extract(wireMock.baseUrl() + "/r");
		extractor().extract(wireMock.baseUrl() + ARTICLE_PATH);

		ILoggingEvent violation = eventAt(0);
		ILoggingEvent ordinary = eventAt(1);
		assertEquals(Level.WARN, violation.getLevel());
		assertTrue(violation.getFormattedMessage().contains("정책 위반"), violation.getFormattedMessage());
		assertEquals(Level.INFO, ordinary.getLevel());
		assertFalse(ordinary.getFormattedMessage().contains("정책 위반"), ordinary.getFormattedMessage());
	}

	private ILoggingEvent eventAt(int index) {
		assertTrue(logs.list.size() > index, "로그가 부족하다: " + logs.list);
		return logs.list.get(index);
	}

	private static ch.qos.logback.classic.Logger logger() {
		return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(JsoupArticleExtractor.class);
	}

	private void stubArticle(String path) {
		wireMock.stubFor(get(path).willReturn(aResponse()
				.withHeader("Content-Type", "text/html; charset=utf-8")
				.withBody(Fixtures.read("article-semantic.html"))));
	}

	private static JsoupArticleExtractor extractor() {
		SafeUrlPolicy policy = new SafeUrlPolicy(new UrlNormalizer(), TEST_RESOLVER);
		return new JsoupArticleExtractor(policy, new ExtractorProperties(
				Duration.ofSeconds(5), 3000, 200, 3, "AINewsDigest/1.0 (+https://github.com/JongGurlHan)"));
	}
}
