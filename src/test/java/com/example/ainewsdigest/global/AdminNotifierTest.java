package com.example.ainewsdigest.global;

import com.example.ainewsdigest.delivery.SendResult;
import com.example.ainewsdigest.delivery.TelegramProperties;
import com.example.ainewsdigest.support.FakeMessenger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 이 클래스의 계약은 <b>어떤 경우에도 밖으로 나가지 않는다</b>이다. 장애를 알리는 코드가 장애를 만들면
 * 배치 전체가 그 자리에서 멈추고, 원래 알리려던 문제는 영영 보고되지 않는다.
 */
class AdminNotifierTest {

	private static final long ADMIN = 777_000L;

	private final FakeMessenger messenger = new FakeMessenger();

	@Test
	void sendsFailureToTheAdminChat() {
		notifier(String.valueOf(ADMIN)).notifyFailure("생성 실패", "OpenAI 500");

		assertEquals(1, this.messenger.countTo(ADMIN));
		String html = this.messenger.htmlTo(ADMIN).getFirst();
		assertTrue(html.contains("생성 실패"), html);
		assertTrue(html.contains("OpenAI 500"), html);
	}

	/** 등급이 본문에서 구분되지 않으면 관리자가 경고와 실패를 눈으로 가릴 수 없다. */
	@Test
	void warningAndFailureAreLabelledDifferently() {
		AdminNotifier notifier = notifier(String.valueOf(ADMIN));

		notifier.notifyFailure("제목", "내용");
		notifier.notifyWarning("제목", "내용");

		assertEquals(2, this.messenger.countTo(ADMIN));
		assertFalse(this.messenger.htmlTo(ADMIN).get(0).equals(this.messenger.htmlTo(ADMIN).get(1)));
	}

	/** 설정이 비어 있는 것은 정상 상태다(관리자 알림을 쓰지 않는 배포). 예외로 배치를 세우면 안 된다. */
	@Test
	void blankAdminChatIdIsIgnoredSilently() {
		assertDoesNotThrow(() -> notifier("").notifyFailure("생성 실패", "OpenAI 500"));

		assertEquals(0, this.messenger.count());
	}

	@Test
	void nullAdminChatIdIsIgnoredSilently() {
		assertDoesNotThrow(() -> notifier(null).notifyFailure("생성 실패", "OpenAI 500"));

		assertEquals(0, this.messenger.count());
	}

	/** 오타 하나로 배치가 죽어서는 안 된다. 보내지 못한 사실은 로그로만 남는다. */
	@Test
	void nonNumericAdminChatIdIsIgnoredSilently() {
		assertDoesNotThrow(() -> notifier("@myself").notifyFailure("생성 실패", "OpenAI 500"));

		assertEquals(0, this.messenger.count());
	}

	/**
	 * {@code Messenger}는 예외를 던지지 않기로 계약했지만(ADR-016), 그 계약이 깨졌을 때 여기서 튀면
	 * 스케줄러의 다음 단계가 통째로 날아간다. 알림은 본 기능보다 항상 덜 중요하다.
	 */
	@Test
	void messengerExceptionIsNotPropagated() {
		AdminNotifier notifier = new AdminNotifier((chatId, html) -> {
			throw new IllegalStateException("텔레그램 죽음");
		}, telegram(String.valueOf(ADMIN)));

		assertDoesNotThrow(() -> notifier.notifyFailure("생성 실패", "OpenAI 500"));
	}

	/** 발송 실패({@code SendResult})도 마찬가지다. 실패 결과를 예외로 바꾸지 않는다. */
	@Test
	void failedSendResultIsNotPropagated() {
		this.messenger.returning(new SendResult.Failed("400", "Bad Request", false));

		assertDoesNotThrow(() -> notifier(String.valueOf(ADMIN)).notifyFailure("생성 실패", "OpenAI 500"));
	}

	/**
	 * 예외 메시지에는 {@code <}, {@code &}가 흔히 섞인다. 이스케이프하지 않으면 텔레그램이 400을 돌려주고
	 * <b>알림 자체가 통째로 사라진다</b> — 장애가 있는 날에만 터지는 종류의 버그다 (ADR-009).
	 */
	@Test
	void escapesHtmlSpecialCharacters() {
		notifier(String.valueOf(ADMIN)).notifyFailure("<b>제목</b>", "a < b && c > d");

		String html = this.messenger.htmlTo(ADMIN).getFirst();
		assertTrue(html.contains("&lt;b&gt;제목&lt;/b&gt;"), html);
		assertTrue(html.contains("a &lt; b &amp;&amp; c &gt; d"), html);
	}

	/** 스택트레이스가 섞인 detail 하나로 4,096자를 넘기면 알림이 400으로 실패한다. */
	@Test
	void truncatesAnOverlongDetail() {
		notifier(String.valueOf(ADMIN)).notifyFailure("생성 실패", "x".repeat(10_000));

		assertTrue(this.messenger.htmlTo(ADMIN).getFirst().length() < 4_096,
				() -> "길이=" + this.messenger.htmlTo(ADMIN).getFirst().length());
	}

	private AdminNotifier notifier(String adminChatId) {
		return new AdminNotifier(this.messenger, telegram(adminChatId));
	}

	private static TelegramProperties telegram(String adminChatId) {
		return new TelegramProperties(null, null, adminChatId, null, null);
	}
}
