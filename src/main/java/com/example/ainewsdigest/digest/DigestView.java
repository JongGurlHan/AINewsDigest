package com.example.ainewsdigest.digest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 다이제스트 하나의 조회용 DTO. 봇의 {@code /start} 응답(step 8)과 웹 아카이브(step 10)가 함께 쓴다.
 *
 * @param messageText 텔레그램으로 나갔던 조립 완료 HTML. 봇이 최근호를 다시 보낼 때 그대로 재사용한다 —
 *                    여기서 다시 조립하면 실제 발송분과 문구가 어긋난다
 * @param status      콘텐츠의 성격이지 발송 여부가 아니다. 발송 여부는 {@code sentAt}이다 (ADR-014)
 */
public record DigestView(LocalDate digestDate, DigestStatus status, String messageText, Instant sentAt,
		List<DigestItemView> items) {

	public DigestView {
		items = List.copyOf(items);
	}

	/** {@code Digest.items}가 지연 로딩이므로 <b>트랜잭션 안에서만</b> 호출해야 한다. */
	static DigestView from(Digest digest) {
		return new DigestView(digest.getDigestDate(), digest.getStatus(), digest.getMessageText(),
				digest.getSentAt(), digest.getItems().stream().map(DigestItemView::from).toList());
	}

	/** "오늘의 AI 뉴스는 없습니다"로 나간 날 (ADR-013). */
	public boolean isEmpty() {
		return this.status == DigestStatus.EMPTY;
	}
}
