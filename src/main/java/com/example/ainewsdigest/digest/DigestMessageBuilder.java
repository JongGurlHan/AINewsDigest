package com.example.ainewsdigest.digest;

import com.example.ainewsdigest.curation.SummarizedArticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 요약 결과를 텔레그램 HTML 메시지 한 덩어리로 조립한다 (ADR-009).
 *
 * <p>외부 의존이 없는 순수 로직이지만 <b>이 단계의 버그는 매일 아침 발송 실패로 직결된다.</b>
 * 텔레그램은 HTML이 조금이라도 깨지면 400 Bad Request를 돌려주고, 400은 재시도 대상이 아니라
 * 같은 문자열이 구독자 전원에게 그대로 반복된다. 그래서 여기서 지키는 규칙이 셋이다:
 *
 * <ol>
 *   <li><b>이스케이프는 {@code & < >} 셋뿐.</b> MarkdownV2 이스케이프(백슬래시)를 섞지 마라 —
 *       HTML 모드에서는 백슬래시가 화면에 그대로 나온다</li>
 *   <li><b>길이는 "after entities parsing" 기준으로 센다.</b> 태그와 href의 URL은 0자다.
 *       원시 {@code html.length()}로 재면 긴 URL 때문에 멀쩡한 기사가 잘려 나간다</li>
 *   <li><b>자르는 것은 이스케이프 전 원문뿐이다.</b> 조립된 HTML을 뒤에서 자르면 {@code &amp;}가
 *       {@code &am}으로 쪼개지고 태그가 {@code <a hre}에서 끊긴다. 둘 다 400이다</li>
 * </ol>
 *
 * <p>텔레그램 API를 호출하지 않는다. 문자열만 만든다 — 발송은 step 7·9의 몫이다.
 */
@Component
public class DigestMessageBuilder {

	private static final Logger log = LoggerFactory.getLogger(DigestMessageBuilder.class);

	/** 로케일을 고정한다. 서버 기본 로케일에 따라 날짜 표기가 흔들리면 안 된다. */
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREA);

	private static final String HEADER = "오늘의 AI 뉴스";

	/** 불릿은 텔레그램이 지원하는 태그가 아니라 그냥 글자다. HTML {@code <ul>}은 무시되고 사라진다. */
	private static final String BULLET = "• ";

	private static final String SOURCE_PREFIX = "출처: ";

	/** 불릿 한 개는 한 줄이어야 한다. 요약 안의 줄바꿈·연속 공백은 한 칸으로 눌러 붙인다. */
	private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

	/** 모델이 스스로 붙인 리스트 마커. 그대로 두면 {@code • - 내용}이 된다. */
	private static final Pattern LEADING_LIST_MARKER = Pattern.compile("^[-*•·]+\\s*");

	private final MessageProperties properties;

	public DigestMessageBuilder(MessageProperties properties) {
		this.properties = properties;
	}

	/**
	 * 요약 결과를 메시지로 만든다. {@code articles}가 비면 "뉴스 없음" 메시지를 만든다 (침묵하지 않는다).
	 *
	 * <p>입력 순서(= 점수 내림차순)를 그대로 따르고, 상한을 넘으면 <b>가장 마지막 항목부터</b> 뺀다.
	 * 점수가 가장 낮은 것이 먼저 나간다. 번호는 뺀 뒤에 다시 매긴다.
	 */
	public DigestMessage build(LocalDate date, List<SummarizedArticle> articles) {
		if (articles == null || articles.isEmpty()) {
			return assemble(date, List.of());
		}
		List<SummarizedArticle> included = new ArrayList<>(articles);
		while (true) {
			DigestMessage message = assemble(date, included);
			if (message.visibleLength() <= properties.maxVisibleLength()) {
				return message;
			}
			// 1건까지 줄였는데도 넘으면 더 뺄 것이 없다. 그때만 요약을 자른다 (최후 수단).
			if (included.size() == 1) {
				return truncateToFit(date, included.get(0), message.visibleLength());
			}
			SummarizedArticle dropped = included.remove(included.size() - 1);
			log.info("메시지가 상한을 넘어 최하위 항목을 제외했다 ({}자 > {}자, 제외={})",
					message.visibleLength(), properties.maxVisibleLength(), dropped.titleKo());
		}
	}

	/**
	 * 1건짜리 메시지의 요약을 잘라 상한에 맞춘다.
	 *
	 * <p><b>자르는 대상은 이스케이프 전 원문이고, 자른 뒤에 다시 조립해 길이를 재측정한다.</b>
	 * 재측정을 생략하면 안 된다 — 이스케이프가 절단 뒤에 오므로 조립 결과가 예상과 달라질 수 있다.
	 * 넘으면 넘은 만큼 더 줄여 반복하고, 매 회 최소 1코드포인트는 줄어들어 반드시 끝난다.
	 */
	private DigestMessage truncateToFit(LocalDate date, SummarizedArticle article, int visibleLength) {
		String summary = article.summaryKo();
		int target = summary.codePointCount(0, summary.length());
		int overflow = visibleLength - properties.maxVisibleLength();
		while (true) {
			target = Math.max(0, target - Math.max(overflow, 1));
			DigestMessage candidate = assemble(date, List.of(withSummary(article, cut(summary, target))));
			if (candidate.visibleLength() <= properties.maxVisibleLength() || target == 0) {
				log.warn("1건만 남았는데도 상한을 넘어 요약을 잘랐다 ({}자 -> {}자, 제목={})",
						summary.codePointCount(0, summary.length()), target, article.titleKo());
				return candidate;
			}
			overflow = candidate.visibleLength() - properties.maxVisibleLength();
		}
	}

	private static SummarizedArticle withSummary(SummarizedArticle article, String summaryKo) {
		return new SummarizedArticle(article.article(), article.score(), article.titleKo(), summaryKo);
	}

	/**
	 * 코드포인트 경계에서 자른다. {@code substring(0, n)}으로 char 수를 세어 자르면 이모지의
	 * UTF-16 서로게이트 페어가 반으로 쪼개져 깨진 문자가 만들어지고, 텔레그램이 그 메시지를 거부한다.
	 */
	private static String cut(String text, int codePoints) {
		if (codePoints <= 0) {
			return "";
		}
		if (text.codePointCount(0, text.length()) <= codePoints) {
			return text;
		}
		return text.substring(0, text.offsetByCodePoints(0, codePoints));
	}

	/**
	 * 메시지 전체를 처음부터 다시 만든다. 항목을 뺄 때마다 여기로 되돌아오므로 번호는 자동으로 1부터 다시 매겨진다.
	 *
	 * <pre>
	 * &lt;b&gt;오늘의 AI 뉴스&lt;/b&gt; · 2026년 8월 5일
	 *
	 * &lt;b&gt;1. {titleKo}&lt;/b&gt;
	 * • {요약 첫 문장}
	 * • {요약 둘째 문장}
	 * 출처: &lt;a href="{url}"&gt;{sourceDomain}&lt;/a&gt;
	 * </pre>
	 *
	 * 줄바꿈은 {@code \n}이다. 텔레그램은 {@code <br>}를 지원하지 않는다.
	 */
	private DigestMessage assemble(LocalDate date, List<SummarizedArticle> articles) {
		Writer writer = new Writer();
		writer.tag("<b>");
		writer.text(HEADER);
		writer.tag("</b>");
		writer.text(" · " + DATE_FORMAT.format(date));
		if (articles.isEmpty()) {
			writer.text("\n\n" + properties.emptyText());
		}
		for (int index = 0; index < articles.size(); index++) {
			SummarizedArticle article = articles.get(index);
			writer.text("\n\n");
			writer.tag("<b>");
			writer.text((index + 1) + ". " + article.titleKo());
			writer.tag("</b>");
			writer.text("\n");
			for (String line : bulletLines(article.summaryKo())) {
				writer.text(BULLET + line + "\n");
			}
			// 링크는 LLM이 아니라 우리가 붙인다. 원문 URL을 쓰고 화면에는 도메인만 노출한다 (ADR-009).
			// normalizedUrl은 중복 판정용이라 추적 파라미터가 제거된 값이다. 클릭 대상은 원문이어야 한다.
			// "출처: "는 링크 밖에 둔다 — 안에 넣으면 라벨까지 파랗게 물들고 탭 영역이 넓어진다.
			writer.text(SOURCE_PREFIX);
			writer.link(article.article().url(), article.article().sourceDomain());
		}
		return new DigestMessage(writer.html(), writer.visibleLength(), articles.size(), List.copyOf(articles));
	}

	/**
	 * 요약 한 덩어리를 불릿 한 줄씩으로 쪼갠다. 문장 부호가 하나도 없으면 통째로 한 줄이다.
	 *
	 * <p><b>마침표만 보고 자르면 안 된다.</b> 요약에는 고유명사가 원문 표기로 들어오고
	 * ({@code 0.6B/1.3B}, {@code setup.sh}, {@code leansearch.net}) 그 점들은 문장 끝이 아니다.
	 * 문장 끝의 조건은 "문장 부호 뒤가 공백이거나 문자열의 끝"이다 — 위 세 경우는 모두 뒤에 글자가 붙어 있다.
	 *
	 * <p>반환하는 각 줄에는 줄바꿈이 없다. 요약 안에 들어온 줄바꿈을 그대로 두면 불릿 하나가 두 줄로
	 * 흐르면서 들여쓰기가 어긋나 오히려 읽기 어려워진다.
	 */
	private static List<String> bulletLines(String summary) {
		if (summary == null || summary.isBlank()) {
			return List.of();
		}
		List<String> lines = new ArrayList<>();
		int start = 0;
		int cursor = 0;
		while (cursor < summary.length()) {
			if (!isSentenceEnd(summary.charAt(cursor))) {
				cursor++;
				continue;
			}
			// "...!?" 처럼 이어진 부호와 그 뒤의 닫는 괄호·인용부호까지 한 문장으로 묶는다.
			int end = cursor + 1;
			while (end < summary.length() && isSentenceEnd(summary.charAt(end))) {
				end++;
			}
			while (end < summary.length() && isCloser(summary.charAt(end))) {
				end++;
			}
			if (end < summary.length() && !Character.isWhitespace(summary.charAt(end))) {
				cursor = end;
				continue;
			}
			addLine(lines, summary.substring(start, end));
			start = end;
			cursor = end;
		}
		addLine(lines, summary.substring(start));
		return lines;
	}

	private static void addLine(List<String> lines, String sentence) {
		String line = LEADING_LIST_MARKER.matcher(WHITESPACE_RUN.matcher(sentence).replaceAll(" ").strip())
				.replaceFirst("");
		if (!line.isEmpty()) {
			lines.add(line);
		}
	}

	private static boolean isSentenceEnd(char character) {
		return character == '.' || character == '!' || character == '?' || character == '…';
	}

	private static boolean isCloser(char character) {
		return character == ')' || character == ']' || character == '"' || character == '\''
				|| character == '”' || character == '’' || character == '」' || character == '』';
	}

	/**
	 * HTML과 "보이는 길이"를 <b>같이</b> 쌓는다.
	 *
	 * <p>조립을 끝낸 뒤 완성된 HTML을 다시 훑어 길이를 재는 방법도 있지만, 그러려면 태그·엔티티·href를
	 * 구분하는 파서를 하나 더 만들어야 하고 그 파서가 조립기와 어긋나는 순간 상한 판정이 통째로 틀린다.
	 * 붙이는 쪽에서 세면 어긋날 여지가 없다.
	 */
	private static final class Writer {

		private final StringBuilder html = new StringBuilder(512);

		private int visibleLength;

		/**
		 * 화면에 보이는 평문. 이스케이프해 붙이되 길이는 <b>이스케이프 전</b> 기준으로 센다 —
		 * {@code &amp;}는 파싱 후 {@code &} 한 글자가 되므로 1자다.
		 */
		void text(String plain) {
			escapeInto(plain, this.html);
			this.visibleLength += plain.length();
		}

		/** 태그는 파싱 후 사라진다. 0자로 센다. */
		void tag(String tag) {
			this.html.append(tag);
		}

		/**
		 * href의 URL은 화면에 보이지 않으므로 0자다. 이걸 세면 500자짜리 URL 하나 때문에
		 * 멀쩡한 기사가 잘려 나간다.
		 *
		 * <p>URL도 {@code & < >}를 이스케이프한다. 쿼리 파라미터의 {@code &}는 흔하고,
		 * 이 URL은 우리가 고른 것이 아니라 <b>HN에 아무나 올린 것</b>이다 (ADR-017).
		 * 큰따옴표는 엔티티가 아니라 퍼센트 인코딩으로 없앤다 — 속성 밖으로 빠져나가는 것을 막으면서
		 * 텔레그램 파서의 엔티티 지원 범위에 기대지 않는다.
		 */
		void link(String url, String label) {
			this.html.append("<a href=\"");
			escapeInto(url.replace("\"", "%22"), this.html);
			this.html.append("\">");
			text(label);
			this.html.append("</a>");
		}

		String html() {
			return this.html.toString();
		}

		int visibleLength() {
			return this.visibleLength;
		}

		/**
		 * 텔레그램 HTML 모드에서 특수문자는 {@code &}, {@code <}, {@code >} 셋뿐이다.
		 *
		 * <p>한 번에 훑는다. {@code replace("<", "&lt;").replace("&", "&amp;")}처럼 이어 붙이면
		 * 순서 하나가 틀린 순간 {@code &lt;}가 다시 {@code &amp;lt;}가 되어 화면에 "&lt;"라는 글자가 나간다.
		 * 한 글자씩 처리하면 그 실수가 구조적으로 불가능하다.
		 */
		private static void escapeInto(String text, StringBuilder out) {
			for (int index = 0; index < text.length(); index++) {
				char character = text.charAt(index);
				switch (character) {
					case '&' -> out.append("&amp;");
					case '<' -> out.append("&lt;");
					case '>' -> out.append("&gt;");
					default -> out.append(character);
				}
			}
		}
	}
}
