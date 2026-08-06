package com.example.ainewsdigest.delivery;

import com.example.ainewsdigest.digest.Digest;
import com.example.ainewsdigest.subscription.Subscriber;
import com.example.ainewsdigest.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@Import(TestcontainersConfig.class)
class DeliveryLogRepositoryTest {

	private static final Instant NOW = Instant.parse("2026-08-05T22:30:00Z");

	@Autowired
	private DeliveryLogRepository deliveryLogs;

	@Autowired
	private TestEntityManager entityManager;

	/**
	 * 발송 재개 시 이 목록에 있는 구독자를 건너뛴다 (ADR-014). 다른 다이제스트의 로그나 FAILED 로그가
	 * 섞이면 아직 못 받은 사람이 영영 못 받거나, 이미 받은 사람이 또 받는다.
	 */
	@Test
	void returnsOnlySuccessfulSubscriberIdsOfGivenDigest() {
		Digest today = entityManager.persistAndFlush(
				Digest.pending(LocalDate.of(2026, 8, 6), "오늘", NOW));
		Digest yesterday = entityManager.persistAndFlush(
				Digest.pending(LocalDate.of(2026, 8, 5), "어제", NOW));
		Subscriber delivered = entityManager.persistAndFlush(Subscriber.subscribe(1L, "web", NOW));
		Subscriber failed = entityManager.persistAndFlush(Subscriber.subscribe(2L, "web", NOW));
		Subscriber other = entityManager.persistAndFlush(Subscriber.subscribe(3L, "web", NOW));

		deliveryLogs.save(DeliveryLog.success(today.getId(), delivered.getId(), NOW));
		deliveryLogs.save(DeliveryLog.failure(today.getId(), failed.getId(), "429", NOW));
		deliveryLogs.save(DeliveryLog.success(yesterday.getId(), other.getId(), NOW));
		entityManager.flush();
		entityManager.clear();

		List<Long> resumeSkipList =
				deliveryLogs.findSubscriberIdsByDigestIdAndStatus(today.getId(), DeliveryStatus.SUCCESS);
		assertEquals(List.of(delivered.getId()), resumeSkipList);
	}

	@Test
	void persistsFailureWithErrorCode() {
		Digest digest = entityManager.persistAndFlush(
				Digest.pending(LocalDate.of(2026, 8, 6), "오늘", NOW));
		Subscriber subscriber = entityManager.persistAndFlush(Subscriber.subscribe(9L, "web", NOW));

		DeliveryLog saved = deliveryLogs.save(DeliveryLog.failure(digest.getId(), subscriber.getId(), "403", NOW));
		entityManager.flush();
		entityManager.clear();

		DeliveryLog reloaded = deliveryLogs.findById(saved.getId()).orElseThrow();
		assertEquals(DeliveryStatus.FAILED, reloaded.getStatus());
		assertEquals("403", reloaded.getErrorCode());
		assertEquals(digest.getId(), reloaded.getDigestId());
		assertEquals(subscriber.getId(), reloaded.getSubscriberId());
		assertEquals(NOW, reloaded.getAttemptedAt());
	}
}
