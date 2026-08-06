package com.example.ainewsdigest.delivery;

import com.example.ainewsdigest.global.AdminNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 텔레그램 업데이트를 롱폴링으로 받아 {@link BotCommandHandler}에 넘기는 백그라운드 러너 (ADR-008).
 *
 * <p><b>루프는 어떤 경우에도 살아 있어야 한다.</b> 여기서 예외가 새어 스레드가 죽으면 구독 접수가
 * 영구 정지되고, 랜딩의 구독 버튼이 아무 반응 없는 링크가 된다. 복구 수단은 재기동뿐이며 그 사실을
 * 알아채는 장치도 없다.
 *
 * <h2>쉬는 것은 실패일 때뿐이다</h2>
 * 롱폴링에서 "타임아웃까지 기다렸는데 0건"은 가장 흔한 <b>정상</b> 응답이다. 여기서 쉬면 응답성만
 * 그만큼 나빠진다. 반대로 실패에 고정 간격으로 재시도하면 텔레그램 장애가 길어질 때 시간당 720회를
 * 계속 때린다. 그래서 {@link PollResult}가 둘을 구분하고(ADR-016), 실패에만 지수 백오프를 건다:
 * {@code min(failureBackoff × 2^(연속실패-1), maxBackoff)} = 5s, 10s, 20s, 40s, 60s, 60s…
 *
 * <h2>offset은 메모리에만 둔다</h2>
 * 재시작하면 0부터 시작해 텔레그램이 보관 중인 최근 24시간 업데이트를 다시 받는다. 그래도 되는 이유는
 * {@code SubscriptionService}가 멱등이기 때문이다. offset 테이블을 만들면 스키마와 마이그레이션만 늘고
 * 얻는 것이 없다.
 *
 * <p><b>알려진 한계.</b> {@link UpdateSource}는 텍스트 없는 업데이트(사진·스티커)를 걸러서 주므로 그
 * {@code updateId}가 여기까지 오지 않는다. 그런 업데이트만 대기열에 남으면 빈 {@code Updates}가
 * 돌아오고 offset이 전진하지 않아, 다음 폴링이 즉시 같은 응답을 받는다. 없애려면 포트가 "본 것 중
 * 가장 큰 updateId"를 함께 돌려줘야 한다.
 */
@Component
@ConditionalOnProperty(prefix = "ainewsdigest.telegram.polling", name = "enabled",
		havingValue = "true", matchIfMissing = true)
public class TelegramUpdatePoller implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(TelegramUpdatePoller.class);

	/** 지수의 상한. {@code 2^20}이면 어떤 설정값이든 maxBackoff를 넘어서므로 shift 오버플로를 볼 일이 없다. */
	private static final int MAX_EXPONENT = 20;

	private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);

	private final UpdateSource updateSource;

	private final BotCommandHandler handler;

	private final PollingProperties polling;

	private final TelegramProperties telegram;

	private final Sleeper sleeper;

	private final AdminNotifier notifier;

	private volatile boolean running;

	private ExecutorService executor;

	/** 아래 셋은 폴링 스레드 하나만 건드린다. 다른 스레드와 공유하지 말 것. */
	private long offset;

	private int consecutiveFailures;

	private boolean alerted;

	public TelegramUpdatePoller(UpdateSource updateSource, BotCommandHandler handler, PollingProperties polling,
			TelegramProperties telegram, Sleeper sleeper, AdminNotifier notifier) {
		this.updateSource = updateSource;
		this.handler = handler;
		this.polling = polling;
		this.telegram = telegram;
		this.sleeper = sleeper;
		this.notifier = notifier;
	}

	/** 데몬 스레드 하나로 돈다. 폴링이 JVM 종료를 붙잡지 않게 한다. */
	@Override
	public void start() {
		if (this.running) {
			return;
		}
		this.running = true;
		this.executor = Executors.newSingleThreadExecutor((runnable) -> {
			Thread thread = new Thread(runnable, "telegram-poller");
			thread.setDaemon(true);
			return thread;
		});
		this.executor.execute(this::runLoop);
		log.info("텔레그램 롱폴링 시작 (timeout={}s, backoff={}~{})", pollSeconds(),
				this.polling.failureBackoff(), this.polling.maxBackoff());
	}

	@Override
	public void stop() {
		if (!this.running) {
			return;
		}
		this.running = false;
		// 인터럽트로 깨운다. 롱폴링 대기 중이거나 백오프로 자는 중일 수 있다.
		this.executor.shutdownNow();
		try {
			this.executor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		this.executor = null;
		log.info("텔레그램 롱폴링 중단");
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	/**
	 * <b>여기서 루프를 끝내는 것은 종료 요청뿐이다.</b> 예외로 빠져나가면 구독 기능이 영구 정지된다.
	 * {@link UpdateSource}는 예외를 던지지 않기로 계약했지만, 계약이 깨졌을 때 조용히 멈추는 쪽이
	 * 훨씬 나쁘다.
	 */
	private void runLoop() {
		while (this.running && !Thread.currentThread().isInterrupted()) {
			try {
				pollOnce();
			}
			catch (RuntimeException ex) {
				log.warn("폴링 사이클에서 예외가 났다. 루프는 계속한다: {}", ex.toString());
			}
		}
		log.info("텔레그램 폴링 루프 종료 (offset={})", this.offset);
	}

	/** 한 사이클. 테스트가 스레드 없이 직접 호출한다. */
	void pollOnce() {
		PollResult result = this.updateSource.getUpdates(this.offset, pollSeconds());
		switch (result) {
			case PollResult.Updates updates -> onUpdates(updates.updates());
			case PollResult.Failure failure -> onFailure(failure.reason());
		}
	}

	/**
	 * 한 건의 처리 실패가 나머지를 날리지 않게 한다. 그리고 <b>처리 결과와 무관하게 offset은 전진시킨다</b> —
	 * 처리하지 못한 업데이트를 계속 다시 받아봐야 같은 자리에서 또 실패할 뿐이고, 그 사이 뒤에 쌓인
	 * 정상 명령이 전부 막힌다.
	 */
	private void onUpdates(List<TelegramUpdate> updates) {
		this.consecutiveFailures = 0;
		this.alerted = false;
		if (updates.isEmpty()) {
			// 롱폴링의 가장 흔한 정상 응답이다. 쉬지 않는다.
			return;
		}
		long highest = this.offset - 1;
		for (TelegramUpdate update : updates) {
			highest = Math.max(highest, update.updateId());
			try {
				this.handler.handle(update);
			}
			catch (RuntimeException ex) {
				log.warn("업데이트 처리 실패 (updateId={}): {}", update.updateId(), ex.toString());
			}
		}
		this.offset = highest + 1;
	}

	/**
	 * <b>offset을 건드리지 않는다.</b> 실패했다는 것은 업데이트를 못 받았다는 뜻이고, 여기서 전진시키면
	 * 그 구간의 {@code /start}가 텔레그램에서 확인 처리돼 영영 사라진다.
	 */
	private void onFailure(String reason) {
		this.consecutiveFailures++;
		alertOnce(reason);
		Duration backoff = backoff();
		log.debug("폴링 {}회 연속 실패. {} 쉬고 재시도한다 (offset={}): {}",
				this.consecutiveFailures, backoff, this.offset, reason);
		this.sleeper.sleep(backoff);
	}

	/**
	 * 임계값에 도달한 <b>순간 한 번만</b> 남긴다. 복구될 때까지 매 사이클 알리면 장애 중에 알림이 쏟아져
	 * 그 자체가 2차 장애가 된다. 복구되면({@code Updates} 수신) 플래그가 내려가 다음 장애에 다시 울린다.
	 *
	 * <p>"언제 알릴 것인가"의 판정은 전부 이 메서드 안에서 끝난다. {@link AdminNotifier}는 채널일 뿐이므로
	 * 저쪽에 억제 로직을 또 두지 않는다 — 두 벌이 되면 어느 쪽이 침묵시켰는지 알 수 없게 된다.
	 *
	 * <p>{@code reason}은 {@code TelegramClient}가 마스킹한 문자열이다. 봇 토큰이 그대로 관리자 방에
	 * 발송되지 않는 근거가 그쪽에 있다.
	 */
	private void alertOnce(String reason) {
		if (this.alerted || this.consecutiveFailures < this.polling.alertThreshold()) {
			return;
		}
		this.alerted = true;
		log.error("텔레그램 폴링이 {}회 연속 실패했다. 구독 접수가 멈춰 있다. 마지막 사유: {}",
				this.consecutiveFailures, reason);
		this.notifier.notifyFailure("텔레그램 폴링 연속 실패",
				"폴링이 " + this.consecutiveFailures + "회 연속 실패했다. 구독 접수(/start)가 멈춰 있다.\n"
						+ "마지막 사유: " + reason);
	}

	private Duration backoff() {
		long base = this.polling.failureBackoff().toMillis();
		long max = this.polling.maxBackoff().toMillis();
		int exponent = Math.min(this.consecutiveFailures - 1, MAX_EXPONENT);
		return Duration.ofMillis(Math.min(base << exponent, max));
	}

	private int pollSeconds() {
		return (int) this.telegram.pollTimeout().toSeconds();
	}

	/** 테스트 검증용. offset이 전진했는지/멈췄는지가 이 클래스의 핵심 계약이다. */
	long offset() {
		return this.offset;
	}
}
