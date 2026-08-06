package com.example.ainewsdigest.subscription;

import com.example.ainewsdigest.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Import(TestcontainersConfig.class)}가 없으면 DB가 붙지 않는다. 슬라이스 안에서도 Flyway가
 * 돌고 {@code ddl-auto: validate}가 걸려 있으므로, 매핑이 {@code V1__init.sql}과 어긋나면 여기서 깨진다.
 */
@DataJpaTest
@Import(TestcontainersConfig.class)
class SubscriberRepositoryTest {

	private static final Instant NOW = Instant.parse("2026-08-06T07:30:00Z");

	@Autowired
	private SubscriberRepository subscribers;

	@Autowired
	private TestEntityManager entityManager;

	@Test
	void savesAndFindsByChatId() {
		subscribers.save(Subscriber.subscribe(42L, "web", NOW));
		entityManager.flush();
		entityManager.clear();

		Subscriber found = subscribers.findByChatId(42L).orElseThrow();
		assertEquals(42L, found.getChatId());
		assertEquals("web", found.getSource());
		assertEquals(SubscriberStatus.ACTIVE, found.getStatus());
		assertEquals(NOW, found.getSubscribedAt());
		assertNull(found.getUnsubscribedAt());
		assertTrue(found.isActive());
	}

	/** chat_id UNIQUE — 같은 사용자가 /start를 두 번 보내도 행이 둘로 늘어나면 안 된다. */
	@Test
	void rejectsDuplicateChatId() {
		subscribers.saveAndFlush(Subscriber.subscribe(100L, "web", NOW));

		Subscriber duplicate = Subscriber.subscribe(100L, "web", NOW);
		assertThrows(DataIntegrityViolationException.class, () -> subscribers.saveAndFlush(duplicate));
	}

	@Test
	void unsubscribedIsExcludedFromActiveList() {
		subscribers.save(Subscriber.subscribe(1L, "web", NOW));
		Subscriber leaving = subscribers.save(Subscriber.subscribe(2L, "web", NOW));
		leaving.unsubscribe(NOW.plusSeconds(60));
		entityManager.flush();
		entityManager.clear();

		List<Long> activeChatIds = subscribers.findAllByStatus(SubscriberStatus.ACTIVE).stream()
				.map(Subscriber::getChatId)
				.toList();
		assertEquals(List.of(1L), activeChatIds);

		Subscriber reloaded = subscribers.findByChatId(2L).orElseThrow();
		assertFalse(reloaded.isActive());
		assertEquals(SubscriberStatus.UNSUBSCRIBED, reloaded.getStatus());
		assertEquals(NOW.plusSeconds(60), reloaded.getUnsubscribedAt());
	}

	@Test
	void resubscribeReactivatesUnsubscribed() {
		Subscriber subscriber = subscribers.save(Subscriber.subscribe(3L, "web", NOW));
		subscriber.unsubscribe(NOW.plusSeconds(60));
		subscriber.resubscribe(NOW.plusSeconds(120));
		entityManager.flush();
		entityManager.clear();

		Subscriber reloaded = subscribers.findByChatId(3L).orElseThrow();
		assertTrue(reloaded.isActive());
		assertEquals(NOW.plusSeconds(120), reloaded.getSubscribedAt());
		assertNull(reloaded.getUnsubscribedAt());
	}
}
