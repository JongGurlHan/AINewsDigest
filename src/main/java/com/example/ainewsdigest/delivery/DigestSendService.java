package com.example.ainewsdigest.delivery;

import com.example.ainewsdigest.digest.Digest;
import com.example.ainewsdigest.digest.DigestRepository;
import com.example.ainewsdigest.subscription.Subscriber;
import com.example.ainewsdigest.subscription.SubscriberRepository;
import com.example.ainewsdigest.subscription.SubscriberStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 저장된 미발송 다이제스트를 활성 구독자 전원에게 보낸다 (ARCHITECTURE "다이제스트 발송" 흐름).
 *
 * <p>다이제스트를 만들지 않는다. 만드는 것은 {@code DigestGenerationService}(07:00)의 몫이고 여기는
 * 07:30에 <b>저장된 것을 읽어 보내기만</b> 한다.
 *
 * <h2>발송 대상은 {@code sentAt}으로 고른다</h2>
 * {@code status == PENDING}으로 고르면 EMPTY가 조용히 누락되어 "뉴스 없음"인 날 아무것도 나가지 않는다.
 * 침묵하지 않는 것이 PRD의 약속이므로 PENDING·EMPTY 둘 다 대상이고, 판정 축은 {@code sentAt} 하나다
 * (ADR-014).
 *
 * <h2>트랜잭션 경계</h2>
 * <b>{@code send()}에 {@code @Transactional}을 붙이지 마라.</b> 이 메서드는 구독자 수만큼 텔레그램 HTTP
 * 호출을 한다. 전체를 감싸면 네트워크 대기 중에 DB 커넥션을 점유하고(CLAUDE.md CRITICAL), 100번째에서
 * 실패했을 때 앞선 99건의 발송 기록이 통째로 롤백된다. 기록이 사라지면 재실행이 그 99명에게 다시 보낸다.
 *
 * <p>그래서 텔레그램 호출은 트랜잭션 <b>밖</b>에서 하고, {@link DeliveryLog} 기록과 구독자 상태 변경만
 * 구독자 한 명 단위의 짧은 트랜잭션으로 커밋한다. 이 분리는 재개 스킵(아래)과 한 쌍이다 — 로그가 즉시
 * 커밋돼야 재실행 시 스킵 대상으로 조회된다.
 *
 * <h2>보장 수준은 at-least-once다</h2>
 * 텔레그램 호출과 로그 커밋 사이에 프로세스가 죽은 1건은 실제 발송 여부를 알 수 없어 재실행 시 중복될 수
 * 있다. exactly-once는 포기하고, 이미 SUCCESS 로그가 있는 구독자를 건너뛰는 것으로 중복을 억제한다
 * (ADR-014).
 *
 * <h2>알림은 여기서 하지 않는다</h2>
 * 실패율 판정도 관리자 알림도 스케줄러(step 11)의 몫이다. 이 클래스는 {@link SendSummary}를 돌려주는
 * 것까지만 한다.
 */
@Service
public class DigestSendService {

	private static final Logger log = LoggerFactory.getLogger(DigestSendService.class);

	/** {@code delivery_log.error_code}가 {@code varchar(50)}이다. */
	private static final int MAX_ERROR_CODE_LENGTH = 50;

	private static final String BLOCKED_CODE = "403";

	private static final String RATE_LIMITED_CODE = "429";

	/** 지수의 상한. 어떤 설정값이든 이 이상 밀어붙일 일이 없고, shift 오버플로를 볼 이유도 없다. */
	private static final int MAX_EXPONENT = 20;

	private final DigestRepository digests;

	private final SubscriberRepository subscribers;

	private final DeliveryLogRepository deliveryLogs;

	private final Messenger messenger;

	private final Sleeper sleeper;

	private final DeliveryProperties properties;

	private final TransactionTemplate transactions;

	public DigestSendService(DigestRepository digests, SubscriberRepository subscribers,
			DeliveryLogRepository deliveryLogs, Messenger messenger, Sleeper sleeper,
			DeliveryProperties properties, PlatformTransactionManager transactionManager) {
		this.digests = digests;
		this.subscribers = subscribers;
		this.deliveryLogs = deliveryLogs;
		this.messenger = messenger;
		this.sleeper = sleeper;
		this.properties = properties;
		this.transactions = new TransactionTemplate(transactionManager);
		// 구독자 단위 커밋은 이 클래스의 핵심 계약이다. 호출부가 실수로 트랜잭션 안에서 불러도
		// 경계가 합쳐지지 않도록 REQUIRES_NEW로 고정한다.
		this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 해당 날짜의 미발송 다이제스트(PENDING 또는 EMPTY)를 발송한다.
	 *
	 * <p>이미 {@code sentAt}이 채워져 있으면 아무것도 하지 않는다 (멱등성). 07:30 발송이 두 번 도는 일은
	 * 없어야 하지만, 관리자의 수동 재실행은 언제든 있을 수 있다.
	 */
	public SendSummary send(LocalDate date) {
		Optional<Digest> found = this.digests.findByDigestDate(date);
		if (found.isEmpty()) {
			// 관리자 알림은 스케줄러가 한다. 여기서는 사실만 올려보낸다.
			log.warn("{} 다이제스트가 없다. 발송할 것이 없다.", date);
			return new SendSummary(false, 0, 0, 0, 0, false);
		}
		Digest digest = found.get();
		if (digest.isSent()) {
			log.info("{} 다이제스트는 이미 발송됐다 (sentAt={}). 아무것도 하지 않는다.", date, digest.getSentAt());
			return new SendSummary(true, 0, 0, 0, 0, true);
		}

		List<Subscriber> active = this.subscribers.findAllByStatus(SubscriberStatus.ACTIVE);
		List<Subscriber> targets = resumeTargets(digest.getId(), active);
		int skipped = active.size() - targets.size();
		int succeeded = 0;
		for (Subscriber subscriber : targets) {
			if (deliver(digest.getId(), subscriber, digest.getMessageText())) {
				succeeded++;
			}
		}
		int failed = targets.size() - succeeded;

		boolean sentAtRecorded = shouldMarkSent(succeeded, skipped, targets.size());
		if (sentAtRecorded) {
			markSent(digest.getId());
		}
		else {
			// 여기서 sentAt을 채우면 멱등성 체크가 재실행을 영구히 막는다 (ADR-018).
			log.error("{} 다이제스트 발송이 전원 실패했다 ({}명). sent_at을 남기지 않아 재실행 여지를 둔다.",
					date, targets.size());
		}
		log.info("{} 발송 종료: 시도 {}명, 성공 {}명, 실패 {}명, 스킵 {}명, sent_at 기록={}",
				date, targets.size(), succeeded, failed, skipped, sentAtRecorded);
		return new SendSummary(true, targets.size(), skipped, succeeded, failed, sentAtRecorded);
	}

	/**
	 * 활성 구독자 중 <b>이 다이제스트로 이미 성공한 사람을 뺀다</b> (ADR-014).
	 *
	 * <p>없으면 순회 도중 죽은 실행을 재시작할 때 앞선 구독자들이 같은 다이제스트를 두 번 받는다.
	 * 구독자 단위 트랜잭션은 기록의 롤백만 막을 뿐 재실행을 막지 못한다.
	 */
	private List<Subscriber> resumeTargets(Long digestId, List<Subscriber> active) {
		Set<Long> alreadySent = new HashSet<>(this.deliveryLogs
				.findSubscriberIdsByDigestIdAndStatus(digestId, DeliveryStatus.SUCCESS));
		return active.stream()
				.filter((subscriber) -> !alreadySent.contains(subscriber.getId()))
				.toList();
	}

	/**
	 * {@code markSent} 호출 조건 (ADR-018). <b>순회를 끝냈다는 이유만으로 호출하지 않는다.</b>
	 *
	 * <ul>
	 *   <li>{@code succeeded > 0} — 한 명이라도 받았으면 그 다이제스트는 나간 것이다</li>
	 *   <li>{@code skipped > 0} — 앞선 실행이 이미 보낸 재개 실행이다. 남은 전원이 실패해도 발송된 것으로 본다</li>
	 *   <li>{@code attempted == 0} — 보낼 사람이 없어 끝난 것. 채우지 않으면 그날 다이제스트가 아카이브에서
	 *       영영 사라지고 매일 재시도 대상으로 남는다. "보낼 사람이 없는 것"과 "보내려다 실패한 것"은 다르다</li>
	 * </ul>
	 */
	private static boolean shouldMarkSent(int succeeded, int skipped, int attempted) {
		return succeeded > 0 || skipped > 0 || attempted == 0;
	}

	/**
	 * 구독자 한 명에게 보낸다. 재시도까지 여기서 끝내고 <b>성공 여부만</b> 돌려준다.
	 *
	 * <p>{@link Messenger}는 예외를 던지지 않기로 계약했다 (ADR-016). 네 분기를 각각 다르게 다뤄야 하므로
	 * 결과 타입 그대로 분기한다:
	 *
	 * <ul>
	 *   <li>403 — 봇을 차단했거나 대화를 지운 사람이다. 매일 재시도할 이유가 없어 즉시 해지한다</li>
	 *   <li>429 — {@code retry_after}만큼 기다렸다 다시 보낸다. 단, <b>상한을 넘는 값은 따르지 않는다</b></li>
	 *   <li>5xx·I/O — 지수 백오프로 다시 보낸다</li>
	 *   <li>그 외 4xx — 다시 보내도 같은 답이 온다. 재시도하지 않는다</li>
	 * </ul>
	 */
	private boolean deliver(Long digestId, Subscriber subscriber, String html) {
		int attempts = 0;
		int rateLimitRetries = 0;
		while (true) {
			attempts++;
			SendResult result = this.messenger.send(subscriber.getChatId(), html);
			switch (result) {
				case SendResult.Success ignored -> {
					recordSuccess(digestId, subscriber);
					return true;
				}
				case SendResult.Blocked blocked -> {
					log.info("차단된 구독자를 해지한다 (chatId={}): {}", subscriber.getChatId(), blocked.description());
					unsubscribeAndRecordFailure(digestId, subscriber);
					return false;
				}
				case SendResult.RateLimited limited -> {
					Optional<Duration> wait = rateLimitWait(limited, rateLimitRetries);
					if (wait.isEmpty()) {
						log.warn("레이트리밋을 기다리지 않고 넘어간다 (chatId={}, retryAfter={}s, 재시도 {}회)",
								subscriber.getChatId(), limited.retryAfterSeconds(), rateLimitRetries);
						recordFailure(digestId, subscriber, RATE_LIMITED_CODE);
						return false;
					}
					rateLimitRetries++;
					this.sleeper.sleep(wait.get());
				}
				case SendResult.Failed failure -> {
					if (!failure.retryable() || attempts >= this.properties.maxRetries()) {
						log.warn("발송 실패 (chatId={}, {}회 시도, code={}): {}", subscriber.getChatId(),
								attempts, failure.errorCode(), failure.description());
						recordFailure(digestId, subscriber, failure.errorCode());
						return false;
					}
					this.sleeper.sleep(backoff(attempts));
				}
			}
		}
	}

	/**
	 * 429를 기다릴지 판단한다. 빈 값이면 기다리지 않고 실패로 남긴다.
	 *
	 * <p><b>텔레그램이 준 {@code retry_after}를 그대로 믿지 않는다.</b> 큰 값이 오면 그 시간만큼 배치 전체가
	 * 멈춘다 — 한 명 때문에 나머지 구독자의 아침 발송이 통째로 밀린다.
	 */
	private Optional<Duration> rateLimitWait(SendResult.RateLimited limited, int usedRetries) {
		if (usedRetries >= this.properties.rateLimitRetries()) {
			return Optional.empty();
		}
		Duration wait = Duration.ofSeconds(Math.max(limited.retryAfterSeconds(), 0));
		return (wait.compareTo(this.properties.maxRetryAfter()) > 0) ? Optional.empty() : Optional.of(wait);
	}

	private Duration backoff(int attempts) {
		long base = this.properties.retryBackoff().toMillis();
		return Duration.ofMillis(base << Math.min(attempts - 1, MAX_EXPONENT));
	}

	private void recordSuccess(Long digestId, Subscriber subscriber) {
		this.transactions.executeWithoutResult((status) ->
				this.deliveryLogs.save(DeliveryLog.success(digestId, subscriber.getId(), Instant.now())));
	}

	private void recordFailure(Long digestId, Subscriber subscriber, String errorCode) {
		this.transactions.executeWithoutResult((status) -> this.deliveryLogs
				.save(DeliveryLog.failure(digestId, subscriber.getId(), cut(errorCode), Instant.now())));
	}

	/**
	 * 로그 기록과 해지를 <b>한 트랜잭션에서</b> 커밋한다. 둘이 갈라져 해지만 남으면 그 실패가 이력에서
	 * 사라지고, 로그만 남으면 죽은 구독자에게 내일 아침 또 보낸다.
	 *
	 * <p>상태 변경은 {@code subscriber.unsubscribe()}로만 한다. 구독자는 다른 도메인의 엔티티이므로
	 * 여기서 필드를 직접 건드리지 않는다 (ARCHITECTURE "도메인 간 접근").
	 */
	private void unsubscribeAndRecordFailure(Long digestId, Subscriber subscriber) {
		this.transactions.executeWithoutResult((status) -> {
			Instant now = Instant.now();
			subscriber.unsubscribe(now);
			this.subscribers.save(subscriber);
			this.deliveryLogs.save(DeliveryLog.failure(digestId, subscriber.getId(), BLOCKED_CODE, now));
		});
	}

	/**
	 * 순회가 끝난 뒤 한 번만 부른다. 다이제스트를 다시 읽어 영속 상태에서 바꾼다 — 순회 중에 들고 있던
	 * 인스턴스는 트랜잭션 밖에서 읽은 준영속 객체다.
	 */
	private void markSent(Long digestId) {
		this.transactions.executeWithoutResult((status) -> this.digests.findById(digestId)
				.ifPresent((digest) -> digest.markSent(Instant.now())));
	}

	/** 텔레그램이 무엇을 돌려주든 컬럼 폭 때문에 발송 기록이 통째로 날아가서는 안 된다. */
	private static String cut(String errorCode) {
		if (errorCode == null || errorCode.length() <= MAX_ERROR_CODE_LENGTH) {
			return errorCode;
		}
		return errorCode.substring(0, errorCode.offsetByCodePoints(0, MAX_ERROR_CODE_LENGTH));
	}

	/**
	 * 발송 한 회차의 결과. 스케줄러(step 11)가 이 값으로 헬스체크 핑과 관리자 알림을 판정한다.
	 *
	 * @param digestFound     그날 다이제스트가 있었는가. false면 생성이 실패했다는 뜻이다
	 * @param totalSubscribers <b>실제로 발송을 시도한</b> 구독자 수 (재개 스킵분 제외). step 11의 실패율
	 *                         경고가 이 값을 분모로 쓴다 — 스킵분을 포함시키면 재개 실행에서 실패율이
	 *                         실제보다 낮게 계산돼 경고가 묻힌다
	 * @param skipped         이미 SUCCESS 기록이 있어 건너뛴 구독자 수
	 * @param succeeded       발송에 성공한 구독자 수
	 * @param failed          재시도까지 하고도 실패한 구독자 수
	 * @param sentAtRecorded  이 호출이 끝난 시점에 {@code sent_at}이 채워져 있는가 (ADR-018).
	 *                        전원 실패면 false다. <b>이미 발송된 다이제스트로 재호출된 경우도 true다</b> —
	 *                        이 값의 뜻은 "발송 완료로 기록되었는가"이지 "이번에 markSent를 불렀는가"가
	 *                        아니다. 후자로 쓰면 멱등 재실행이 failure 알림으로 잘못 보고된다
	 */
	public record SendSummary(boolean digestFound, int totalSubscribers, int skipped, int succeeded, int failed,
			boolean sentAtRecorded) {
	}
}
