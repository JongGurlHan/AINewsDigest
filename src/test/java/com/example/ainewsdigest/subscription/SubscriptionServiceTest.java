package com.example.ainewsdigest.subscription;

import com.example.ainewsdigest.subscription.SubscriptionService.SubscribeOutcome;
import com.example.ainewsdigest.support.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 PostgreSQL(Testcontainers)에서 검증한다 (ADR-011). payload 절단은 {@code varchar(50)} 제약에
 * 실제로 INSERT가 나야만 의미가 있으므로 각 검증에서 {@code flush}로 쓰기를 강제한다.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class SubscriptionServiceTest {

	@Autowired
	private SubscriptionService subscriptions;

	@Autowired
	private SubscriberRepository subscribers;

	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void registersNewSubscriberAsActiveWithPayloadAsSource() {
		SubscribeOutcome outcome = this.subscriptions.start(100L, "web");

		assertEquals(SubscribeOutcome.NEW, outcome);
		Subscriber subscriber = reload(100L);
		assertEquals(SubscriberStatus.ACTIVE, subscriber.getStatus());
		assertEquals("web", subscriber.getSource());
		assertNotNull(subscriber.getSubscribedAt());
		assertNull(subscriber.getUnsubscribedAt());
	}

	/**
	 * 폴러의 offset은 메모리에만 있어 재시작 시 텔레그램이 최근 24시간 업데이트를 다시 보낸다 (ADR-008).
	 * 그때마다 행이 늘면 같은 사람에게 다이제스트가 여러 번 나간다.
	 */
	@Test
	void repeatedStartKeepsExactlyOneSubscriberRow() {
		assertEquals(SubscribeOutcome.NEW, this.subscriptions.start(200L, "web"));
		assertEquals(SubscribeOutcome.ALREADY_ACTIVE, this.subscriptions.start(200L, "web"));
		assertEquals(SubscribeOutcome.ALREADY_ACTIVE, this.subscriptions.start(200L, "web"));

		this.entityManager.flush();
		assertEquals(1, this.subscribers.count());
	}

	@Test
	void startAfterStopReactivatesTheSameRow() {
		this.subscriptions.start(300L, "web");
		this.subscriptions.stop(300L);
		assertEquals(SubscriberStatus.UNSUBSCRIBED, reload(300L).getStatus());

		SubscribeOutcome outcome = this.subscriptions.start(300L, "web");

		assertEquals(SubscribeOutcome.REACTIVATED, outcome);
		Subscriber subscriber = reload(300L);
		assertEquals(SubscriberStatus.ACTIVE, subscriber.getStatus());
		assertNull(subscriber.getUnsubscribedAt());
		assertEquals(1, this.subscribers.count());
	}

	@Test
	void stopOnUnknownChatIdDoesNothing() {
		assertDoesNotThrow(() -> this.subscriptions.stop(999L));

		this.entityManager.flush();
		assertEquals(0, this.subscribers.count());
	}

	/**
	 * 딥링크 규격(1~64자)은 <b>클라이언트가 만드는 링크</b>에만 적용된다. 대화창에 직접 친 긴 문자열이
	 * 그대로 저장되면 {@code varchar(50)} 위반으로 구독이 조용히 실패한다.
	 */
	@Test
	void truncatesOverlongPayloadInsteadOfFailingTheSubscription() {
		SubscribeOutcome outcome = this.subscriptions.start(400L, "a".repeat(200));

		assertEquals(SubscribeOutcome.NEW, outcome);
		Subscriber subscriber = reload(400L);
		assertEquals(SubscriberStatus.ACTIVE, subscriber.getStatus());
		assertTrue(subscriber.getSource().length() <= 50, "source=" + subscriber.getSource().length());
	}

	/** 한글·이모지·따옴표는 허용 문자가 아니다. 전부 걸러져 비면 source는 null로 남는다. */
	@Test
	void keepsSubscriptionWhenPayloadHasNoAllowedCharacters() {
		SubscribeOutcome outcome = this.subscriptions.start(500L, "한글 \"따옴표\" 😀");

		assertEquals(SubscribeOutcome.NEW, outcome);
		Subscriber subscriber = reload(500L);
		assertEquals(SubscriberStatus.ACTIVE, subscriber.getStatus());
		assertNull(subscriber.getSource());
	}

	/** 섞여 들어온 경우 허용 문자만 남는다. */
	@Test
	void keepsOnlyAllowedCharactersFromAMixedPayload() {
		this.subscriptions.start(600L, "웹-배너_2 😀");

		assertEquals("-_2", reload(600L).getSource());
	}

	/** flush로 실제 INSERT/UPDATE를 강제하고 clear로 1차 캐시를 비운다. 컬럼 폭 위반은 DB까지 가야 드러난다. */
	private Subscriber reload(long chatId) {
		this.entityManager.flush();
		this.entityManager.clear();
		return this.subscribers.findByChatId(chatId).orElseThrow();
	}
}
