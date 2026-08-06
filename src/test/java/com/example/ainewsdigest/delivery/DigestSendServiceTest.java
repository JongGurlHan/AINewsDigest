package com.example.ainewsdigest.delivery;

import com.example.ainewsdigest.delivery.DigestSendService.SendSummary;
import com.example.ainewsdigest.digest.Digest;
import com.example.ainewsdigest.digest.DigestItem;
import com.example.ainewsdigest.digest.DigestRepository;
import com.example.ainewsdigest.digest.DigestStatus;
import com.example.ainewsdigest.subscription.Subscriber;
import com.example.ainewsdigest.subscription.SubscriberRepository;
import com.example.ainewsdigest.subscription.SubscriberStatus;
import com.example.ainewsdigest.support.FakeMessenger;
import com.example.ainewsdigest.support.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 발송 통합 테스트. {@link Messenger}는 페이크로 갈아끼우고 DB만 실제 PostgreSQL이다.
 *
 * <p><b>{@code @Transactional}을 붙이지 않는다.</b> 이 클래스가 검증하려는 것 중에 "구독자 단위로 즉시
 * 커밋되는가"가 있는데, 테스트 트랜잭션으로 전부 감싸버리면 그 커밋 경계가 통째로 사라진다. 대신
 * 각 테스트 전후로 손으로 지운다 — 커밋된 행이 다음 테스트에 남으면 안 된다.
 *
 * <p>{@link Sleeper}도 페이크다. 재시도 분기를 실제로 기다리면 이 클래스 하나가 수십 초짜리가 된다.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class DigestSendServiceTest {

	/** 다른 테스트가 쓰는 날짜와 겹치지 않게 둔다. 이 클래스만 커밋을 남긴다. */
	private static final LocalDate DATE = LocalDate.of(2026, 5, 20);

	private static final Instant GENERATED = Instant.parse("2026-05-20T07:00:00Z");

	private static final String HTML = "<b>2026-05-20 AI 다이제스트</b>";

	private static final long CHAT_A = 1_001L;

	private static final long CHAT_B = 1_002L;

	private static final long CHAT_C = 1_003L;

	@Autowired
	private DigestRepository digests;

	@Autowired
	private SubscriberRepository subscribers;

	@Autowired
	private DeliveryLogRepository deliveryLogs;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private final FakeMessenger messenger = new FakeMessenger();

	private final RecordingSleeper sleeper = new RecordingSleeper();

	@BeforeEach
	@AfterEach
	void clearEverything() {
		// delivery_log가 digest·subscriber를 참조하므로 순서를 지킨다.
		this.deliveryLogs.deleteAll();
		this.digests.deleteAll();
		this.subscribers.deleteAll();
	}

	@Test
	void sendsToEveryActiveSubscriberAndLogsSuccess() {
		Digest digest = savePendingDigest();
		List<Subscriber> all = saveActiveSubscribers(CHAT_A, CHAT_B, CHAT_C);

		SendSummary summary = service().send(DATE);

		assertTrue(summary.digestFound());
		assertEquals(3, summary.totalSubscribers());
		assertEquals(3, summary.succeeded());
		assertEquals(0, summary.failed());
		assertEquals(0, summary.skipped());
		assertEquals(3, this.messenger.count());
		assertEquals(List.of(HTML), this.messenger.htmlTo(CHAT_A));
		assertEquals(3, successLogCount(digest));
		assertEquals(all.size(), logsFor(digest).size());
		assertTrue(logsFor(digest).stream().allMatch((entry) -> entry.getStatus() == DeliveryStatus.SUCCESS));
	}

	@Test
	void markSentTurnsPendingIntoSent() {
		savePendingDigest();
		saveActiveSubscribers(CHAT_A);

		SendSummary summary = service().send(DATE);

		assertTrue(summary.sentAtRecorded());
		Digest saved = reload();
		assertEquals(DigestStatus.SENT, saved.getStatus());
		assertNotNull(saved.getSentAt());
	}

	/** 관리자의 수동 재실행이 이미 나간 다이제스트를 한 번 더 보내면 구독자 전원이 같은 것을 두 번 받는다. */
	@Test
	void alreadySentDigestSendsNothing() {
		Digest digest = savePendingDigest();
		markSent(digest);
		saveActiveSubscribers(CHAT_A, CHAT_B);

		SendSummary summary = service().send(DATE);

		assertTrue(summary.digestFound());
		assertEquals(0, summary.totalSubscribers());
		assertEquals(0, this.messenger.count());
		assertTrue(logsFor(digest).isEmpty());
	}

	/** 생성이 실패한 날. 예외로 터지면 스케줄러가 알림을 보낼 기회조차 잃는다. */
	@Test
	void missingDigestIsReportedNotThrown() {
		saveActiveSubscribers(CHAT_A);

		SendSummary summary = service().send(DATE);

		assertFalse(summary.digestFound());
		assertEquals(0, summary.totalSubscribers());
		assertFalse(summary.sentAtRecorded());
		assertEquals(0, this.messenger.count());
	}

	/** EMPTY를 빼면 "뉴스 없음"인 날 아무것도 나가지 않는다. 침묵하지 않는 것이 PRD의 약속이다. */
	@Test
	void emptyDigestIsSentToo() {
		saveEmptyDigest();
		saveActiveSubscribers(CHAT_A, CHAT_B);

		SendSummary summary = service().send(DATE);

		assertEquals(2, summary.succeeded());
		assertEquals(List.of("오늘의 AI 뉴스는 없습니다."), this.messenger.htmlTo(CHAT_A));
	}

	/** EMPTY를 SENT로 덮으면 그날 뉴스가 없었다는 기록이 사라져 임계값 튜닝 근거가 없어진다 (ADR-013·014). */
	@Test
	void sentEmptyDigestKeepsItsStatus() {
		saveEmptyDigest();
		saveActiveSubscribers(CHAT_A);

		service().send(DATE);

		Digest saved = reload();
		assertEquals(DigestStatus.EMPTY, saved.getStatus());
		assertNotNull(saved.getSentAt());
	}

	/** 봇을 차단한 사람에게 매일 재시도할 이유가 없다. 그 한 명만 해지되고 나머지는 그대로 받는다. */
	@Test
	void blockedSubscriberIsUnsubscribedWhileOthersStillReceiveIt() {
		savePendingDigest();
		saveActiveSubscribers(CHAT_A, CHAT_B, CHAT_C);
		this.messenger.always(CHAT_B, new SendResult.Blocked("bot was blocked by the user"));

		SendSummary summary = service().send(DATE);

		assertEquals(2, summary.succeeded());
		assertEquals(1, summary.failed());
		assertEquals(SubscriberStatus.UNSUBSCRIBED, statusOf(CHAT_B));
		assertEquals(SubscriberStatus.ACTIVE, statusOf(CHAT_A));
		assertEquals(SubscriberStatus.ACTIVE, statusOf(CHAT_C));
		assertEquals(List.of(HTML), this.messenger.htmlTo(CHAT_C));
		// 403은 재시도 대상이 아니다. 한 번 보내고 끝이다.
		assertEquals(1, this.messenger.countTo(CHAT_B));
	}

	@Test
	void blockedSubscriberIsLoggedAsFailedWith403() {
		Digest digest = savePendingDigest();
		saveActiveSubscribers(CHAT_A);
		this.messenger.always(CHAT_A, new SendResult.Blocked("Forbidden: bot was blocked by the user"));

		service().send(DATE);

		DeliveryLog entry = logsFor(digest).getFirst();
		assertEquals(DeliveryStatus.FAILED, entry.getStatus());
		assertEquals("403", entry.getErrorCode());
		assertEquals(subscriberId(CHAT_A), entry.getSubscriberId());
	}

	/** 재시도 가능한 실패는 지수 백오프로 세 번까지다. 넷째 시도를 하면 배치가 한없이 늘어진다. */
	@Test
	void retryableFailureGivesUpAfterMaxRetriesAndMovesOn() {
		Digest digest = savePendingDigest();
		saveActiveSubscribers(CHAT_A, CHAT_B);
		this.messenger.always(CHAT_A, new SendResult.Failed("500", "Internal Server Error", true));

		SendSummary summary = service().send(DATE);

		assertEquals(3, this.messenger.countTo(CHAT_A));
		assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), this.sleeper.slept());
		assertEquals(1, summary.failed());
		assertEquals(1, summary.succeeded());
		assertEquals(List.of(HTML), this.messenger.htmlTo(CHAT_B));
		// 시도는 3회지만 남는 기록은 그 구독자에 대한 실패 1건이다.
		assertEquals(1, logsFor(digest, CHAT_A).size());
		assertEquals("500", logsFor(digest, CHAT_A).getFirst().getErrorCode());
	}

	@Test
	void rateLimitedThenSucceedsIsLoggedAsSuccess() {
		Digest digest = savePendingDigest();
		saveActiveSubscribers(CHAT_A);
		this.messenger.script(CHAT_A, new SendResult.RateLimited(1));

		SendSummary summary = service().send(DATE);

		assertEquals(2, this.messenger.countTo(CHAT_A));
		assertEquals(List.of(Duration.ofSeconds(1)), this.sleeper.slept());
		assertEquals(1, summary.succeeded());
		assertEquals(List.of(DeliveryStatus.SUCCESS),
				logsFor(digest, CHAT_A).stream().map(DeliveryLog::getStatus).toList());
	}

	/**
	 * 상한을 넘는 {@code retry_after}를 그대로 따르면 한 명 때문에 배치 전체가 한 시간 멈춘다.
	 * 그 한 명을 실패로 남기고 다음 구독자로 간다.
	 */
	@Test
	void hugeRetryAfterFailsFastWithoutWaiting() {
		Digest digest = savePendingDigest();
		saveActiveSubscribers(CHAT_A, CHAT_B);
		this.messenger.script(CHAT_A, new SendResult.RateLimited(3600));

		SendSummary summary = service().send(DATE);

		assertEquals(List.of(), this.sleeper.slept());
		assertEquals(1, this.messenger.countTo(CHAT_A));
		assertEquals("429", logsFor(digest, CHAT_A).getFirst().getErrorCode());
		assertEquals(DeliveryStatus.FAILED, logsFor(digest, CHAT_A).getFirst().getStatus());
		assertEquals(1, summary.succeeded());
		assertEquals(List.of(HTML), this.messenger.htmlTo(CHAT_B));
	}

	/**
	 * 트랜잭션 분리 검증. 순회 <b>도중에</b> 별도 트랜잭션으로 조회해 앞선 구독자의 성공 기록이 이미
	 * 커밋돼 있는지 본다. 전체를 한 트랜잭션으로 감쌌다면 다른 커넥션에서는 아직 아무것도 보이지 않고,
	 * 그 구조에서는 중간에 죽었을 때 앞선 발송 기록이 통째로 사라져 재실행이 같은 사람에게 다시 보낸다.
	 *
	 * <p>조회를 {@code REQUIRES_NEW}로 감싸는 것이 이 테스트의 핵심이다. 그냥 읽으면 발송이 트랜잭션
	 * 안으로 들어가도 같은 트랜잭션에 합류해 <b>커밋되지 않은 행까지 보이므로</b> 회귀를 잡지 못한다.
	 */
	@Test
	void earlierDeliveryLogsAreCommittedBeforeTheLoopEnds() {
		Digest digest = savePendingDigest();
		saveActiveSubscribers(CHAT_A, CHAT_B, CHAT_C);
		List<List<Long>> visibleDuringSend = new ArrayList<>();
		this.messenger.always(CHAT_B, new SendResult.Failed("400", "Bad Request", false))
				.observing((message) -> visibleDuringSend.add(inSeparateTransaction(() -> this.deliveryLogs
						.findSubscriberIdsByDigestIdAndStatus(digest.getId(), DeliveryStatus.SUCCESS))));

		SendSummary summary = service().send(DATE);

		assertEquals(3, visibleDuringSend.size());
		assertFalse(visibleDuringSend.getLast().isEmpty(),
				"마지막 발송 시점에 앞선 구독자의 SUCCESS 로그가 아직 커밋되지 않았다");
		assertEquals(2, summary.succeeded());
		assertEquals(1, summary.failed());
		assertEquals(3, logsFor(digest).size());
	}

	@Test
	void unsubscribedSubscribersAreNeverSentTo() {
		Digest digest = savePendingDigest();
		saveActiveSubscribers(CHAT_A);
		Subscriber gone = this.subscribers.save(Subscriber.subscribe(CHAT_B, "web", GENERATED));
		gone.unsubscribe(GENERATED);
		this.subscribers.save(gone);

		SendSummary summary = service().send(DATE);

		assertEquals(1, summary.totalSubscribers());
		assertEquals(List.of(), this.messenger.htmlTo(CHAT_B));
		assertEquals(1, logsFor(digest).size());
	}

	/** 순회 도중 죽은 실행을 재개하는 경우. 이미 받은 사람에게 두 번 보내지 않는다 (ADR-014). */
	@Test
	void resumeSkipsSubscribersThatAlreadySucceeded() {
		Digest digest = savePendingDigest();
		saveActiveSubscribers(CHAT_A, CHAT_B, CHAT_C);
		this.deliveryLogs.save(DeliveryLog.success(digest.getId(), subscriberId(CHAT_B), GENERATED));

		SendSummary summary = service().send(DATE);

		assertEquals(2, summary.totalSubscribers());
		assertEquals(1, summary.skipped());
		assertEquals(2, summary.succeeded());
		assertEquals(List.of(), this.messenger.htmlTo(CHAT_B));
		assertEquals(2, this.messenger.count());
		assertEquals(1, logsFor(digest, CHAT_B).size());
	}

	/** 전원 실패면 sent_at을 비워 둔다. 채우는 순간 멱등성 체크가 재실행을 영구히 막는다 (ADR-018). */
	@Test
	void totalFailureLeavesSentAtNull() {
		savePendingDigest();
		saveActiveSubscribers(CHAT_A, CHAT_B, CHAT_C);
		this.messenger.returning(new SendResult.Failed("400", "Bad Request", false));

		SendSummary summary = service().send(DATE);

		assertEquals(3, summary.failed());
		assertEquals(0, summary.succeeded());
		assertFalse(summary.sentAtRecorded());
		Digest saved = reload();
		assertNull(saved.getSentAt());
		assertEquals(DigestStatus.PENDING, saved.getStatus());
	}

	/** 전원 실패 뒤의 재실행이 실제로 다시 나가야 한다. 여기가 막히면 수동 복구 수단이 사라진다. */
	@Test
	void totalFailureCanBeRetriedByCallingSendAgain() {
		Digest digest = savePendingDigest();
		saveActiveSubscribers(CHAT_A, CHAT_B, CHAT_C);
		this.messenger.returning(new SendResult.Failed("400", "Bad Request", false));
		DigestSendService service = service();
		service.send(DATE);

		this.messenger.returning(new SendResult.Success(1L));
		SendSummary retry = service.send(DATE);

		assertEquals(3, retry.totalSubscribers());
		assertEquals(3, retry.succeeded());
		assertEquals(0, retry.skipped());
		assertTrue(retry.sentAtRecorded());
		assertEquals(6, this.messenger.count());
		assertEquals(3, successLogCount(digest));
		assertNotNull(reload().getSentAt());
	}

	/** 한 명이라도 받았으면 그 다이제스트는 나간 것이다. */
	@Test
	void oneSuccessIsEnoughToRecordSentAt() {
		savePendingDigest();
		saveActiveSubscribers(CHAT_A, CHAT_B, CHAT_C);
		this.messenger.always(CHAT_A, new SendResult.Failed("400", "Bad Request", false))
				.always(CHAT_B, new SendResult.Failed("400", "Bad Request", false));

		SendSummary summary = service().send(DATE);

		assertEquals(1, summary.succeeded());
		assertEquals(2, summary.failed());
		assertTrue(summary.sentAtRecorded());
		assertNotNull(reload().getSentAt());
	}

	/**
	 * "보낼 사람이 없어 끝난 것"과 "보내려다 실패한 것"은 다르다. 여기서 비워 두면 그날 다이제스트가
	 * 아카이브에서 영영 사라지고 매일 재시도 대상으로 남는다 (ADR-018).
	 */
	@Test
	void noActiveSubscribersStillRecordsSentAt() {
		savePendingDigest();

		SendSummary summary = service().send(DATE);

		assertEquals(0, summary.totalSubscribers());
		assertEquals(0, summary.skipped());
		assertTrue(summary.sentAtRecorded());
		assertNotNull(reload().getSentAt());
		assertEquals(0, this.messenger.count());
	}

	/** 재개 실행에서 남은 전원이 실패한 경우. 앞선 실행이 이미 대부분에게 보냈으므로 발송된 것으로 본다. */
	@Test
	void resumeWithSkippedSubscriberRecordsSentAtEvenIfTheRestFail() {
		Digest digest = savePendingDigest();
		saveActiveSubscribers(CHAT_A, CHAT_B, CHAT_C);
		this.deliveryLogs.save(DeliveryLog.success(digest.getId(), subscriberId(CHAT_A), GENERATED));
		this.messenger.returning(new SendResult.Failed("400", "Bad Request", false));

		SendSummary summary = service().send(DATE);

		assertEquals(2, summary.totalSubscribers());
		assertEquals(1, summary.skipped());
		assertEquals(0, summary.succeeded());
		assertEquals(2, summary.failed());
		assertTrue(summary.sentAtRecorded());
		assertNotNull(reload().getSentAt());
	}

	private DigestSendService service() {
		// 백오프 값은 실제로 기다리지 않는다. 주입한 슬리퍼가 받은 값만 기록한다.
		return new DigestSendService(this.digests, this.subscribers, this.deliveryLogs, this.messenger,
				this.sleeper, new DeliveryProperties(3, 2, Duration.ofSeconds(60), Duration.ofSeconds(1)),
				this.transactionManager);
	}

	private Digest savePendingDigest() {
		Digest digest = Digest.pending(DATE, HTML, GENERATED);
		digest.addItem(new DigestItem(1, "제목", "요약", "https://example.com/a", "https://example.com/a",
				"example.com", 5));
		return this.digests.save(digest);
	}

	private void saveEmptyDigest() {
		this.digests.save(Digest.empty(DATE, "오늘의 AI 뉴스는 없습니다.", GENERATED));
	}

	private void markSent(Digest digest) {
		digest.markSent(GENERATED);
		this.digests.save(digest);
	}

	private List<Subscriber> saveActiveSubscribers(long... chatIds) {
		List<Subscriber> saved = new ArrayList<>();
		for (long chatId : chatIds) {
			saved.add(this.subscribers.save(Subscriber.subscribe(chatId, "web", GENERATED)));
		}
		return saved;
	}

	private Digest reload() {
		return this.digests.findByDigestDate(DATE).orElseThrow();
	}

	/** 커밋된 것만 보이는 별도 트랜잭션에서 읽는다. 발송 중에 무엇이 이미 확정됐는지 보려는 것이다. */
	private <T> T inSeparateTransaction(Supplier<T> read) {
		TransactionTemplate template = new TransactionTemplate(this.transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template.execute((status) -> read.get());
	}

	private SubscriberStatus statusOf(long chatId) {
		return this.subscribers.findByChatId(chatId).orElseThrow().getStatus();
	}

	private Long subscriberId(long chatId) {
		return this.subscribers.findByChatId(chatId).orElseThrow().getId();
	}

	private List<DeliveryLog> logsFor(Digest digest) {
		return this.deliveryLogs.findAll().stream()
				.filter((entry) -> entry.getDigestId().equals(digest.getId()))
				.toList();
	}

	private List<DeliveryLog> logsFor(Digest digest, long chatId) {
		Long subscriberId = subscriberId(chatId);
		return logsFor(digest).stream()
				.filter((entry) -> entry.getSubscriberId().equals(subscriberId))
				.toList();
	}

	private int successLogCount(Digest digest) {
		return this.deliveryLogs
				.findSubscriberIdsByDigestIdAndStatus(digest.getId(), DeliveryStatus.SUCCESS).size();
	}

	/** 실제로 기다리지 않고 요청받은 대기 시간만 기록한다. */
	private static final class RecordingSleeper implements Sleeper {

		private final List<Duration> slept = new ArrayList<>();

		List<Duration> slept() {
			return List.copyOf(this.slept);
		}

		@Override
		public void sleep(Duration duration) {
			this.slept.add(duration);
		}
	}
}
