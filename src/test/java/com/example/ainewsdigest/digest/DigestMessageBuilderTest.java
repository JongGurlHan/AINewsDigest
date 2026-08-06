package com.example.ainewsdigest.digest;

import com.example.ainewsdigest.collect.CandidateArticle;
import com.example.ainewsdigest.curation.SummarizedArticle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 스프링 컨텍스트도 DB도 띄우지 않는 순수 단위 테스트.
 *
 * <p>여기서 검증하는 것은 "텔레그램이 이 문자열을 400으로 거부하지 않는가"다. 400은 재시도 대상이 아니라
 * 구독자 전원에게 같은 실패가 반복되므로, 이스케이프·길이·절단 경계는 전부 단언으로 못박는다.
 */
class DigestMessageBuilderTest {

	private static final LocalDate DATE = LocalDate.of(2026, 8, 5);

	private static final int MAX = 4000;

	private final DigestMessageBuilder builder = new DigestMessageBuilder(new MessageProperties(null, null));

	// --- 기본 조립 -------------------------------------------------------

	@Test
	void 세건이면_번호와_제목_요약_도메인링크가_들어간다() {
		DigestMessage message = this.builder.build(DATE, List.of(
				article("첫 소식", "첫 요약이다.", "https://a.com/1", "a.com"),
				article("둘째 소식", "둘째 요약이다.", "https://b.com/2", "b.com"),
				article("셋째 소식", "셋째 요약이다.", "https://c.com/3", "c.com")));

		assertTrue(message.html().startsWith("<b>오늘의 AI 뉴스</b> · 2026년 8월 5일"), message.html());
		assertTrue(message.html().contains("<b>1. 첫 소식</b>\n첫 요약이다.\n<a href=\"https://a.com/1\">a.com</a>"));
		assertTrue(message.html().contains("<b>2. 둘째 소식</b>\n둘째 요약이다.\n<a href=\"https://b.com/2\">b.com</a>"));
		assertTrue(message.html().contains("<b>3. 셋째 소식</b>\n셋째 요약이다.\n<a href=\"https://c.com/3\">c.com</a>"));
		assertEquals(3, message.includedCount());
		assertEquals(3, message.includedArticles().size());
	}

	@Test
	void 기사가_없으면_뉴스없음_문구가_나간다() {
		DigestMessage message = this.builder.build(DATE, List.of());

		assertTrue(message.html().contains("오늘의 AI 뉴스는 없습니다."), message.html());
		assertEquals(0, message.includedCount());
		assertTrue(message.includedArticles().isEmpty());
		assertFalse(message.html().contains("<a "), "항목이 없으면 링크도 없다");
	}

	// --- 이스케이프 ------------------------------------------------------

	@Test
	void 제목의_앰퍼샌드_꺾쇠가_이스케이프된다() {
		DigestMessage message = this.builder.build(DATE, List.of(article("A & B <C> D", "요약.")));

		assertTrue(message.html().contains("<b>1. A &amp; B &lt;C&gt; D</b>"), message.html());
		// & 를 먼저 치환하지 않으면 &lt; 가 &amp;lt; 가 된다
		assertFalse(message.html().contains("&amp;lt;"));
		assertFalse(message.html().contains("&amp;amp;"));
	}

	@Test
	void 요약의_script_태그가_태그로_해석되지_않는다() {
		DigestMessage message = this.builder.build(DATE, List.of(article("제목", "<script>alert(1)</script> 끝.")));

		assertFalse(message.html().contains("<script>"), message.html());
		assertTrue(message.html().contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
		assertTagsBalanced(message.html());
	}

	@Test
	void href_안의_앰퍼샌드가_이스케이프된다() {
		DigestMessage message = this.builder
				.build(DATE, List.of(article("제목", "요약.", "https://a.com/x?p=1&q=2", "a.com")));

		assertTrue(message.html().contains("href=\"https://a.com/x?p=1&amp;q=2\""), message.html());
		assertNoBrokenEntities(message.html());
	}

	@Test
	void 마크다운_이스케이프를_하지_않는다() {
		DigestMessage message = this.builder.build(DATE, List.of(article("제목-1.0", "요약이다. 끝!")));

		// MarkdownV2 감각으로 백슬래시를 넣으면 HTML 모드에서는 화면에 그대로 나온다
		assertFalse(message.html().contains("\\"), message.html());
		assertTrue(message.html().contains("요약이다. 끝!"));
	}

	// --- 길이 계산 -------------------------------------------------------

	@Test
	void href의_URL은_길이에_세지_않는다() {
		String longUrl = "https://a.com/" + "x".repeat(500);
		DigestMessage shortLink = this.builder.build(DATE, List.of(article("제목", "요약.", "https://a.com/1", "a.com")));
		DigestMessage longLink = this.builder.build(DATE, List.of(article("제목", "요약.", longUrl, "a.com")));

		assertEquals(shortLink.visibleLength(), longLink.visibleLength());
		assertTrue(longLink.html().length() > longLink.visibleLength() + 500, "원시 문자열은 URL 때문에 훨씬 길다");
	}

	@Test
	void 태그는_길이에_세지_않는다() {
		DigestMessage message = this.builder.build(DATE, List.of(article("제목", "요약.")));

		// 헤더: "오늘의 AI 뉴스"(9) + " · "(3) + "2026년 8월 5일"(11) = 23
		// 항목: \n\n(2) + "1. "(3) + "제목"(2) + \n(1) + "요약."(3) + \n(1) + "example.com"(11) = 23
		assertEquals(46, message.visibleLength());
		assertTrue(message.html().length() > message.visibleLength());
	}

	@Test
	void 앰퍼샌드_엔티티는_한자로_센다() {
		DigestMessage escaped = this.builder.build(DATE, List.of(article("A&B", "요약.")));
		DigestMessage plain = this.builder.build(DATE, List.of(article("AxB", "요약.")));

		assertTrue(escaped.html().contains("&amp;"));
		assertEquals(plain.visibleLength(), escaped.visibleLength(), "&amp;는 파싱 후 & 한 글자다");
	}

	// --- 길이 초과 처리 --------------------------------------------------

	@Test
	void 상한을_넘으면_최하위_항목부터_제거한다() {
		// 5건 × (제목 200 + 요약 600)이면 4,113자로 상한을 넘는다. 4건이면 3,295자로 들어간다.
		DigestMessage message = this.builder.build(DATE, fiveLongArticles());

		assertEquals(4, message.includedCount());
		assertEquals(4, message.includedArticles().size());
		assertTrue(message.visibleLength() <= MAX, "실제 " + message.visibleLength());
	}

	@Test
	void 항목_제거후_번호를_다시_매긴다() {
		DigestMessage message = this.builder.build(DATE, fiveLongArticles());

		assertTrue(message.html().contains("<b>1. "));
		assertTrue(message.html().contains("<b>2. "));
		assertTrue(message.html().contains("<b>3. "));
		assertTrue(message.html().contains("<b>4. "));
		assertFalse(message.html().contains("<b>5. "), "제거된 항목의 번호가 남으면 안 된다");
	}

	@Test
	void 한건인데_요약이_길면_잘라서_맞춘다() {
		DigestMessage message = this.builder.build(DATE, List.of(article("제목", "가".repeat(5000))));

		assertEquals(1, message.includedCount());
		assertTrue(message.visibleLength() <= MAX, "실제 " + message.visibleLength());
		assertTagsBalanced(message.html());
	}

	@Test
	void 어떤_입력에서도_상한을_넘지_않는다() {
		List<List<SummarizedArticle>> inputs = List.of(
				List.of(),
				fiveLongArticles(),
				List.of(article("제목", "가".repeat(10000))),
				List.of(article("제".repeat(200), "&".repeat(10000))),
				List.of(article("제목", "🚀".repeat(3000))),
				List.of(article("제목", "")),
				manyArticles(20));

		for (List<SummarizedArticle> articles : inputs) {
			DigestMessage message = this.builder.build(DATE, articles);
			assertTrue(message.visibleLength() <= MAX,
					"입력 " + articles.size() + "건에서 " + message.visibleLength() + "자");
			assertNoBrokenEntities(message.html());
			assertTagsBalanced(message.html());
		}
	}

	// --- 절단 경계 -------------------------------------------------------

	@Test
	void 절단이_HTML_엔티티를_쪼개지_않는다() {
		// "가&" 5,000자. 이스케이프 후를 잘랐다면 &amp; 가 &am 으로 쪼개진 채 나간다.
		DigestMessage message = this.builder.build(DATE, List.of(article("제목", "가&".repeat(2500))));

		assertTrue(message.visibleLength() <= MAX);
		assertNoBrokenEntities(message.html());
	}

	@Test
	void 절단이_서로게이트_페어를_쪼개지_않는다() {
		DigestMessage message = this.builder.build(DATE, List.of(article("제목", "가🚀".repeat(2000))));

		assertTrue(message.visibleLength() <= MAX);
		// codePoints()는 짝을 잃은 서로게이트를 그 값 그대로 돌려준다. 온전한 이모지는 0x1F680으로 합쳐진다.
		assertTrue(message.html().codePoints().noneMatch(codePoint -> codePoint >= 0xD800 && codePoint <= 0xDFFF),
				"짝을 잃은 서로게이트가 남아 있으면 텔레그램이 메시지를 거부한다");
	}

	@Test
	void 절단이_태그_중간에서_일어나지_않는다() {
		DigestMessage message = this.builder.build(DATE, List.of(article("제목", "나".repeat(6000))));

		assertTrue(message.html().endsWith("</a>"), "링크가 끝까지 남아야 한다");
		assertTagsBalanced(message.html());
	}

	// --- 저장과의 일치 ---------------------------------------------------

	@Test
	void includedArticles가_실제_메시지_내용과_일치한다() {
		String original = "다".repeat(5000);
		DigestMessage message = this.builder.build(DATE, List.of(article("제목", original)));

		String stored = message.includedArticles().get(0).summaryKo();
		assertTrue(stored.length() < original.length(), "잘린 요약이 담겨야 한다");
		assertTrue(original.startsWith(stored), "원문의 앞부분이어야 한다");
		assertTrue(message.html().contains(stored), "메시지에 들어간 것과 같아야 한다");
		assertEquals(message.includedCount(), message.includedArticles().size());
	}

	// --- 헬퍼 ------------------------------------------------------------

	private static List<SummarizedArticle> fiveLongArticles() {
		return List.of(
				article("제".repeat(200), "요".repeat(600)),
				article("목".repeat(200), "약".repeat(600)),
				article("셋".repeat(200), "삼".repeat(600)),
				article("넷".repeat(200), "사".repeat(600)),
				article("닷".repeat(200), "오".repeat(600)));
	}

	private static List<SummarizedArticle> manyArticles(int count) {
		return java.util.stream.IntStream.range(0, count)
				.mapToObj(index -> article("제목 " + index, "요약 " + index + "이다. " + "내".repeat(300)))
				.toList();
	}

	private static SummarizedArticle article(String titleKo, String summaryKo) {
		return article(titleKo, summaryKo, "https://example.com/a", "example.com");
	}

	private static SummarizedArticle article(String titleKo, String summaryKo, String url, String domain) {
		CandidateArticle candidate = new CandidateArticle("original title", url, url, domain, "Hacker News", 100,
				Instant.parse("2026-08-05T00:00:00Z"), null);
		return new SummarizedArticle(candidate, 5, titleKo, summaryKo);
	}

	/** 벌거벗은 {@code &}도, 잘린 엔티티({@code &am})도 없어야 한다. 둘 다 400을 부른다. */
	private static void assertNoBrokenEntities(String html) {
		for (int index = 0; index < html.length(); index++) {
			if (html.charAt(index) != '&') {
				continue;
			}
			boolean valid = html.startsWith("&amp;", index) || html.startsWith("&lt;", index)
					|| html.startsWith("&gt;", index);
			assertTrue(valid, "잘못된 엔티티: " + html.substring(index, Math.min(index + 6, html.length())));
		}
	}

	/**
	 * 태그가 전부 짝이 맞고, 텔레그램이 지원하는 태그만 쓰였는지 본다.
	 *
	 * <p>평문의 {@code <}·{@code >}는 전부 이스케이프되므로 원시 꺾쇠는 태그 구분자일 때만 나온다.
	 */
	private static void assertTagsBalanced(String html) {
		Deque<String> open = new ArrayDeque<>();
		int index = 0;
		while (index < html.length()) {
			if (html.charAt(index) != '<') {
				assertFalse(html.charAt(index) == '>', "이스케이프되지 않은 > 가 있다");
				index++;
				continue;
			}
			int end = html.indexOf('>', index);
			assertTrue(end > index, "닫히지 않은 태그가 있다: " + html.substring(index));
			String tag = html.substring(index + 1, end);
			if (tag.startsWith("/")) {
				assertFalse(open.isEmpty(), "여는 태그 없이 닫혔다: " + tag);
				assertEquals(tag.substring(1), open.pop(), "태그 짝이 맞지 않는다");
			}
			else {
				String name = tag.split(" ")[0];
				assertTrue(List.of("b", "i", "a", "code", "pre").contains(name), "지원하지 않는 태그: " + name);
				open.push(name);
			}
			index = end + 1;
		}
		assertTrue(open.isEmpty(), "닫히지 않은 태그가 남았다: " + open);
	}
}
