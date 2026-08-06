package com.example.ainewsdigest.digest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 다이제스트 하나의 조회용 DTO. 봇의 {@code /start} 응답(step 8)과 웹 아카이브(step 10)가 함께 쓴다.
 *
 * @param messageText 텔레그램으로 나갔던 조립 완료 HTML. 봇이 최근호를 다시 보낼 때 그대로 재사용한다 —
 *                    여기서 다시 조립하면 실제 발송분과 문구가 어긋난다. <b>웹 화면은 이 값을 쓰지 않는다.</b>
 *                    조립된 HTML 덩어리라 렌더링하려면 {@code th:utext}가 필요하고, 그 순간 LLM이 만든
 *                    문자열이 이스케이프 없이 브라우저에 들어간다. 화면은 {@link #items()}로만 만든다
 * @param status      콘텐츠의 성격이지 발송 여부가 아니다. 발송 여부는 {@code sentAt}이다 (ADR-014)
 */
public record DigestView(LocalDate digestDate, DigestStatus status, String messageText, Instant sentAt,
		List<DigestItemView> items) {

	/** {@code 2026년 8월 5일 (수)}. 로케일을 고정한다 — 서버 기본 로케일에 따라 표기가 갈리면 안 된다. */
	private static final DateTimeFormatter DISPLAY_DATE =
			DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREAN);

	/**
	 * <b>항목 순서를 DTO가 보장한다.</b> {@code Digest.items}의 {@code @OrderBy}(step 0)가 DB에서 오는
	 * 길만 지켜주므로, 손으로 만든 뷰나 다른 경로로 담긴 목록은 순서가 뒤섞인 채 화면에 그대로 나간다.
	 * 여기서 한 번 더 정렬해 두면 템플릿이 정렬 로직을 갖지 않아도 된다.
	 */
	public DigestView {
		items = items.stream().sorted(Comparator.comparingInt(DigestItemView::position)).toList();
	}

	/** {@code Digest.items}가 지연 로딩이므로 <b>트랜잭션 안에서만</b> 호출해야 한다. */
	static DigestView from(Digest digest) {
		return new DigestView(digest.getDigestDate(), digest.getStatus(), digest.getMessageText(),
				digest.getSentAt(), digest.getItems().stream().map(DigestItemView::from).toList());
	}

	/**
	 * 화면에 뿌릴 한글 날짜 표기. <b>템플릿에서 포맷팅하지 않기 위해</b> 여기서 만든다.
	 *
	 * <p>레코드 컴포넌트가 아니라 파생 접근자인 이유: {@code digestDate}가 유일한 출처여야 한다.
	 * 별도 필드로 두면 생성자마다 값을 넘겨야 하고, 한 곳이라도 어긋나면 같은 날짜가 화면마다
	 * 다르게 표시된다. {@link #isEmpty()}도 같은 이유로 파생 접근자다.
	 */
	public String displayDate() {
		return this.digestDate.format(DISPLAY_DATE);
	}

	/** "오늘의 AI 뉴스는 없습니다"로 나간 날 (ADR-013). */
	public boolean isEmpty() {
		return this.status == DigestStatus.EMPTY;
	}
}
