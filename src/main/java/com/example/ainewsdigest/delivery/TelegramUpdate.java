package com.example.ainewsdigest.delivery;

/**
 * 텍스트 메시지 업데이트 한 건. 텔레그램 응답에서 이 서비스가 쓰는 세 필드만 옮겨 담는다.
 *
 * @param updateId offset 전진에 쓴다. 다음 폴링은 {@code updateId + 1}부터 요청한다
 * @param chatId   발송에 필요한 식별자. {@code /start} 순간에만 얻을 수 있다 (ADR-003)
 * @param text     명령 문자열 ({@code /start web} 등). 빈 값은 담기지 않는다
 */
public record TelegramUpdate(long updateId, long chatId, String text) {
}
