package com.example.ainewsdigest.collect;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * jsoup으로 기사 원문을 받아 본문만 골라낸다 (ADR-006).
 *
 * <p>실패는 전부 {@code Optional.empty()}다 — 타임아웃·4xx·5xx·비HTML 응답·리다이렉트 초과,
 * 그리고 {@link SafeUrlPolicy} 위반까지. 페이월·봇 차단으로 실패하는 사이트는 반드시 나오므로
 * 이는 정상 흐름의 일부이고, 예외로 바꾸면 step 6의 크롤링 루프가 기사 하나 때문에 멈춘다 (ADR-016).
 *
 * <p>리다이렉트는 {@code followRedirects(false)}로 두고 <b>직접 따라간다.</b> jsoup의 자동 추적은
 * 중간 홉을 보여주지 않아 검사할 방법이 없다.
 */
@Component
public class JsoupArticleExtractor implements ArticleContentExtractor {

	private static final Logger log = LoggerFactory.getLogger(JsoupArticleExtractor.class);

	private static final int MAX_BODY_SIZE = 2 * 1024 * 1024;

	private static final String HTML_MIME_TYPE = "text/html";

	/** 본문에 섞이면 LLM이 네비게이션 메뉴를 뉴스로 요약한다. 후보를 고르기 전에 먼저 지운다. */
	private static final String NOISE_SELECTOR = "script, style, nav, header, footer, aside, form, noscript";

	private static final List<String> CONTENT_SELECTORS = List.of(
			"article", "main", "[role=main]", ".post-content, .article-body, .entry-content");

	/** 개행을 제외한 연속 공백. */
	private static final Pattern HORIZONTAL_WHITESPACE = Pattern.compile("[^\\S\\n]+");

	private static final Pattern BLANK_LINES = Pattern.compile("\\n{2,}");

	private final SafeUrlPolicy safeUrlPolicy;

	private final ExtractorProperties properties;

	public JsoupArticleExtractor(SafeUrlPolicy safeUrlPolicy, ExtractorProperties properties) {
		this.safeUrlPolicy = safeUrlPolicy;
		this.properties = properties;
	}

	@Override
	public Optional<String> extract(String url) {
		String currentUrl = url;
		for (int hop = 0; hop <= properties.maxRedirects(); hop++) {
			// 네트워크로 나가는 모든 요청 직전에 통과시킨다. 최초 URL에만 적용하면 302 한 번으로 우회된다.
			if (!safeUrlPolicy.isAllowed(currentUrl)) {
				// 정상적인 크롤링 실패와 구분되어야 조사할 수 있다. 그래서 레벨과 문구를 달리한다.
				log.warn("SSRF 정책 위반으로 요청을 차단했다 (url={}, hop={}, 최초 url={})", currentUrl, hop, url);
				return Optional.empty();
			}
			Connection.Response response;
			try {
				response = open(currentUrl);
			}
			catch (IOException | RuntimeException ex) {
				log.info("본문 크롤링 실패 (url={}): {}", currentUrl, ex.toString());
				return Optional.empty();
			}
			if (!isRedirect(response.statusCode())) {
				return content(currentUrl, response);
			}
			Optional<String> target = redirectTarget(currentUrl, response);
			if (target.isEmpty()) {
				return Optional.empty();
			}
			currentUrl = target.get();
		}
		log.info("리다이렉트가 {}홉을 넘었다 (url={})", properties.maxRedirects(), url);
		return Optional.empty();
	}

	/**
	 * {@code ignoreHttpErrors(true)}라 3xx·4xx도 예외가 아니라 응답으로 돌아온다.
	 * 상태 코드와 Content-Type은 호출부가 직접 확인해야 한다.
	 */
	private Connection.Response open(String url) throws IOException {
		return Jsoup.connect(url)
				.timeout((int) properties.timeout().toMillis())
				.userAgent(properties.userAgent())
				.followRedirects(false)
				.ignoreHttpErrors(true)
				.ignoreContentType(true)
				.maxBodySize(MAX_BODY_SIZE)
				.execute();
	}

	private static boolean isRedirect(int statusCode) {
		return statusCode >= HttpURLConnection.HTTP_MULT_CHOICE && statusCode < HttpURLConnection.HTTP_BAD_REQUEST;
	}

	/** {@code Location}을 현재 URL 기준으로 절대화한다. 상대 경로 리다이렉트가 드물지 않다. */
	private static Optional<String> redirectTarget(String currentUrl, Connection.Response response) {
		String location = response.header("Location");
		if (location == null || location.isBlank()) {
			log.info("리다이렉트 응답에 Location이 없다 (url={}, status={})", currentUrl, response.statusCode());
			return Optional.empty();
		}
		try {
			return Optional.of(new URI(currentUrl).resolve(location.trim()).toString());
		}
		catch (URISyntaxException | IllegalArgumentException ex) {
			log.info("Location을 해석하지 못했다 (url={}, location={})", currentUrl, location);
			return Optional.empty();
		}
	}

	private Optional<String> content(String url, Connection.Response response) {
		if (response.statusCode() != HttpURLConnection.HTTP_OK) {
			log.info("본문 크롤링 실패: 상태 코드 {} (url={})", response.statusCode(), url);
			return Optional.empty();
		}
		String contentType = response.contentType();
		if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(HTML_MIME_TYPE)) {
			log.info("본문 크롤링 실패: HTML이 아니다 (url={}, contentType={})", url, contentType);
			return Optional.empty();
		}
		Optional<String> text = extractFromHtml(response.body(), url);
		if (text.isEmpty()) {
			log.info("본문 추출 실패: 본문이 {}자 미만이다 (url={})", properties.minContentLength(), url);
		}
		return text;
	}

	/**
	 * 이 클래스의 진짜 로직은 본문 골라내기다. 네트워크 없이 검증할 수 있도록 분리한다.
	 *
	 * <p>{@code String}이 아니라 {@code Optional}을 돌려주는 이유: 최소 길이 미달 판정(쿠키 배너·페이월
	 * 안내문만 긁힌 경우)이 파싱 결과의 일부이므로, 실패가 값으로 표현되어야 {@link #extract}의 계약과 이어진다.
	 */
	Optional<String> extractFromHtml(String html, String baseUri) {
		Document document = Jsoup.parse(html, baseUri);
		document.select(NOISE_SELECTOR).remove();

		String text = paragraphText(contentElement(document));
		if (text.length() < properties.minContentLength()) {
			return Optional.empty();
		}
		return Optional.of(truncate(text));
	}

	private static Element contentElement(Document document) {
		for (String selector : CONTENT_SELECTORS) {
			Element found = document.selectFirst(selector);
			if (found != null) {
				return found;
			}
		}
		return densestParagraphBlock(document);
	}

	/**
	 * 시맨틱 태그가 없을 때의 대안. {@code <p>}를 직접 자식으로 가진 요소별로 텍스트 길이를 합산해
	 * 가장 큰 것을 고른다.
	 *
	 * <p>후손까지 합산하면 {@code <body>}가 항상 최대가 되어 사이드바·댓글이 전부 본문에 섞인다.
	 */
	private static Element densestParagraphBlock(Document document) {
		Map<Element, Integer> scores = new HashMap<>();
		Element best = null;
		int bestScore = 0;
		for (Element paragraph : document.select("p")) {
			Element parent = paragraph.parent();
			if (parent == null) {
				continue;
			}
			int score = scores.merge(parent, paragraph.text().length(), Integer::sum);
			if (score > bestScore) {
				bestScore = score;
				best = parent;
			}
		}
		return (best != null) ? best : document.body();
	}

	private static String paragraphText(Element content) {
		if (content == null) {
			return "";
		}
		Elements paragraphs = content.select("p");
		String joined = paragraphs.stream()
				.map(Element::text)
				.filter(text -> !text.isBlank())
				.collect(Collectors.joining("\n"));
		return normalizeWhitespace(joined);
	}

	/** 연속 공백과 빈 줄을 하나로 줄인다. 요소 안쪽은 {@code Element#text()}가 이미 정규화해 둔다. */
	private static String normalizeWhitespace(String text) {
		String collapsed = HORIZONTAL_WHITESPACE.matcher(text).replaceAll(" ");
		return BLANK_LINES.matcher(collapsed).replaceAll("\n").trim();
	}

	private String truncate(String text) {
		int limit = properties.maxContentLength();
		return (text.length() <= limit) ? text : text.substring(0, limit);
	}
}
