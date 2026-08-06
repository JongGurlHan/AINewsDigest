package com.example.ainewsdigest.delivery;

/**
 * 발송 한 건의 결과. 실패를 예외가 아니라 반환 타입으로 표현한다 (ADR-016).
 *
 * <p>분기가 넷인 이유는 호출자(step 9)가 넷을 <b>다르게</b> 다뤄야 하기 때문이다 — 차단은 구독 해지,
 * 429는 대기 후 재시도, 5xx는 백오프 재시도, 400은 재시도 금지다. 하나의 실패 타입으로 뭉치면
 * 이 구분이 호출부의 문자열 파싱으로 내려간다.
 */
public sealed interface SendResult {

	record Success(long messageId) implements SendResult {
	}

	/** 403 — 사용자가 봇을 차단했거나 대화를 삭제했다. 구독자를 비활성화해야 한다. */
	record Blocked(String description) implements SendResult {
	}

	/** 429 — retryAfterSeconds만큼 기다린 뒤 재시도해야 한다. */
	record RateLimited(int retryAfterSeconds) implements SendResult {
	}

	/**
	 * 그 외 실패. retryable이면 백오프 후 재시도 가능.
	 *
	 * <p>{@code description}은 step 9·11을 거쳐 <b>관리자 알림으로 텔레그램에 발송된다.</b>
	 * 봇 토큰이 섞이지 않도록 구현체가 마스킹한 문자열만 담는다.
	 */
	record Failed(String errorCode, String description, boolean retryable) implements SendResult {
	}
}
