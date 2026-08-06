package com.example.ainewsdigest.delivery;

/**
 * 메시지 발송 아웃바운드 포트. 서비스 계층은 구현체({@code TelegramClient})를 직접 참조하지 않는다.
 * 테스트는 인메모리 페이크로 대체할 수 있어야 한다.
 */
public interface Messenger {

	/**
	 * 한 명에게 HTML 메시지를 보낸다.
	 *
	 * <p><b>예외를 던지지 않는다 (ADR-016).</b> 구독자 수백 명을 순회하는 발송 루프에서 예외가 튀면
	 * 나머지 구독자 발송이 통째로 중단된다. 실패는 전부 {@link SendResult}로 표현한다.
	 *
	 * @param html {@code parse_mode=HTML} 기준으로 조립된 본문 ({@code DigestMessageBuilder} 산출물)
	 */
	SendResult send(long chatId, String html);
}
