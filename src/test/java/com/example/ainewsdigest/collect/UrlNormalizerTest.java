package com.example.ainewsdigest.collect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 네트워크를 쓰지 않는 순수 단위 테스트. */
class UrlNormalizerTest {

	private final UrlNormalizer normalizer = new UrlNormalizer();

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource(quoteCharacter = '\'', value = {
			// scheme·host 소문자화 (경로는 보존)
			"'HTTPS://Example.COM/A', 'https://example.com/A'",
			// http -> https 통일
			"'http://a.com/x', 'https://a.com/x'",
			// 선행 www. 제거
			"'https://www.a.com/x', 'https://a.com/x'",
			// 기본 포트 제거
			"'https://a.com:443/x', 'https://a.com/x'",
			"'http://a.com:80/x', 'https://a.com/x'",
			"'https://a.com:8443/x', 'https://a.com:8443/x'",
			// fragment 제거
			"'https://a.com/x#intro', 'https://a.com/x'",
			// 남은 쿼리 파라미터는 이름 오름차순 정렬
			"'https://a.com/x?b=2&a=1', 'https://a.com/x?a=1&b=2'",
			// 쿼리가 비면 ? 자체를 제거
			"'https://a.com/x?utm_source=hn', 'https://a.com/x'",
			// 후행 슬래시 제거 (경로가 / 뿐이면 유지)
			"'https://a.com/x/', 'https://a.com/x'",
			"'https://a.com/', 'https://a.com/'",
			"'https://a.com', 'https://a.com/'",
	})
	void normalizesByRule(String rawUrl, String expected) {
		assertEquals(expected, normalizer.normalize(rawUrl));
	}

	@ParameterizedTest(name = "{0} 제거")
	@ValueSource(strings = {
			"utm_source=hn", "utm_medium=social", "utm_campaign=launch", "utm_content=a", "utm_term=b",
			"fbclid=xyz", "gclid=xyz", "ref=producthunt", "ref_src=twsrc", "source=rss",
			"mc_cid=1", "mc_eid=2",
	})
	void dropsTrackingParameters(String trackingParam) {
		assertEquals("https://a.com/x?id=5",
				normalizer.normalize("https://a.com/x?" + trackingParam + "&id=5"));
	}

	/** 같은 기사가 HN과 RSS로 각각 들어와도 한 값으로 모여야 중복 발송이 막힌다. */
	@Test
	void collapsesSameArticleArrivingWithDifferentDecorations() {
		String fromHackerNews = normalizer.normalize("https://a.com/x?utm_source=hn&id=5");
		String fromRss = normalizer.normalize("https://www.a.com/x/?id=5#top");

		assertEquals(fromHackerNews, fromRss);
		assertEquals("https://a.com/x?id=5", fromHackerNews);
	}

	/** 경로를 소문자화하면 서로 다른 문서가 같은 것으로 판정된다. */
	@Test
	void keepsPathCaseSoDifferentDocumentsStayDifferent() {
		assertNotEquals(normalizer.normalize("https://a.com/Post"), normalizer.normalize("https://a.com/post"));
	}

	@Test
	void returnsTrimmedOriginalWhenInputCannotBeParsed() {
		assertEquals("not a url", normalizer.normalize("  not a url  "));
		assertEquals("javascript:alert(1)", normalizer.normalize("javascript:alert(1)"));
		assertEquals("", normalizer.normalize(""));
		assertEquals("", normalizer.normalize(null));
	}

	@ParameterizedTest
	@CsvSource(quoteCharacter = '\'', value = {
			"'https://www.techcrunch.com/2026/x', 'techcrunch.com'",
			"'https://Cursor.com/changelog', 'cursor.com'",
			"'http://a.com:8080/x', 'a.com'",
			"'not a url', ''",
			"'javascript:alert(1)', ''",
	})
	void extractsDisplayDomain(String rawUrl, String expected) {
		assertEquals(expected, normalizer.extractDomain(rawUrl));
	}

	@Test
	void extractDomainReturnsEmptyStringForNull() {
		assertEquals("", normalizer.extractDomain(null));
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://a.com/x", "https://a.com/x", "HTTPS://A.COM" })
	void acceptsHttpUrls(String rawUrl) {
		assertTrue(normalizer.isHttpUrl(rawUrl));
	}

	/**
	 * 이 판별이 파이프라인의 첫 관문이다. 통과시키면 step 10의 아카이브가
	 * {@code th:href}로 렌더링하는데, {@code th:href}는 javascript: 스킴을 막지 않는다.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"javascript:alert(1)", "file:///etc/passwd", "data:text/html,x",
			"not a url", "", "ftp://a.com/x", "//a.com/x",
	})
	void rejectsNonHttpUrls(String rawUrl) {
		assertFalse(normalizer.isHttpUrl(rawUrl));
	}

	@Test
	void isHttpUrlReturnsFalseForNull() {
		assertFalse(normalizer.isHttpUrl(null));
	}
}
