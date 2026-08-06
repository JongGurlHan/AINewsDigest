package com.example.ainewsdigest.digest;

/**
 * 다이제스트 콘텐츠의 성격. <b>발송 여부는 이 값이 아니라 {@code sentAt}으로 판정한다</b> (ADR-014).
 *
 * <p>{@code FAILED}는 스키마의 예약값이며 MVP 흐름에서는 저장되지 않는다. 생성이 실패하면 행 자체를
 * 만들지 않는다 — FAILED로 저장하면 {@code digest_date} UNIQUE와 생성 멱등성 체크에 걸려
 * 그날의 수동 재실행이 영구히 막힌다.
 */
public enum DigestStatus {

	PENDING,
	SENT,
	EMPTY,
	FAILED
}
