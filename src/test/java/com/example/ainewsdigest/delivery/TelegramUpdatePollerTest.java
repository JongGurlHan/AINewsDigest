package com.example.ainewsdigest.delivery;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 스레드 없이 {@code pollOnce()}를 직접 돌린다. 백오프는 <b>주입한 슬리퍼가 받은 값</b>으로 검증한다 —
 * 실제로 기다리면 이 테스트 하나가 수십 초짜리가 된다.
 */
class TelegramUpdatePollerTest {

	private static final Duration BACKOFF = Duration.ofSeconds(5);

	private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

	private final ScriptedUpdateSource source = new ScriptedUpdateSource();

	private final RecordingSleeper sleeper = new RecordingSleeper();

	private final RecordingHandler handler = new RecordingHandler();

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	private ch.qos.logback.classic.Logger logger;

	@BeforeEach
	void attachAppender() {
		this.appender.start();
		this.logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TelegramUpdatePoller.class);
		this.logger.addAppender(this.appender);
	}

	@AfterEach
	void detachAppender() {
		this.logger.detachAppender(this.appender);
		this.appender.stop();
	}

	@Test
	void handlesEveryUpdateAndAdvancesOffsetPastTheHighestId() {
		this.source.enqueue(updates(10L, 11L, 12L));
		TelegramUpdatePoller poller = poller();

		poller.pollOnce();

		assertEquals(List.of(10L, 11L, 12L), this.handler.handledIds());
		assertEquals(13L, poller.offset());
		assertEquals(List.of(), this.sleeper.slept());
	}

	/** 한 건의 처리 실패가 나머지를 날리면 그 폴링에 섞여 있던 다른 구독 요청이 전부 사라진다. */
	@Test
	void keepsProcessingAfterAHandlerThrows() {
		this.source.enqueue(updates(10L, 11L, 12L));
		this.handler.failOn(11L);
		TelegramUpdatePoller poller = poller();

		poller.pollOnce();

		assertEquals(List.of(10L, 11L, 12L), this.handler.handledIds());
		assertEquals(13L, poller.offset());
	}

	/** 실패는 "업데이트를 못 받았다"는 뜻이다. offset을 전진시키면 그 구간의 /start가 영영 사라진다. */
	@Test
	void failureBacksOffAndLeavesTheOffsetAlone() {
		this.source.enqueue(updates(10L), new PollResult.Failure("HTTP 502"));
		TelegramUpdatePoller poller = poller();

		poller.pollOnce();
		poller.pollOnce();

		assertEquals(11L, poller.offset());
		assertEquals(List.of(BACKOFF), this.sleeper.slept());
		// 다음 폴링도 같은 offset으로 요청한다.
		poller.pollOnce();
		assertEquals(List.of(0L, 11L, 11L), this.source.requestedOffsets());
	}

	/** 롱폴링에서 0건은 가장 흔한 정상 응답이다. 여기서 자면 응답성만 그만큼 나빠진다. */
	@Test
	void emptyUpdatesNeverBackOff() {
		this.source.enqueue(new PollResult.Updates(List.of()), new PollResult.Updates(List.of()));
		TelegramUpdatePoller poller = poller();

		poller.pollOnce();
		poller.pollOnce();

		assertEquals(List.of(), this.sleeper.slept());
		assertEquals(0L, poller.offset());
	}

	/** 연속 실패 카운터가 실제로 백오프에 쓰여야 한다. 고정값이면 장애가 길어질 때 시간당 720회를 때린다. */
	@Test
	void backoffGrowsExponentiallyAndStopsAtTheCeiling() {
		this.source.enqueueFailures(6);
		TelegramUpdatePoller poller = poller();

		for (int cycle = 0; cycle < 6; cycle++) {
			poller.pollOnce();
		}

		assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20),
				Duration.ofSeconds(40), MAX_BACKOFF, MAX_BACKOFF), this.sleeper.slept());
	}

	@Test
	void successfulPollResetsTheBackoff() {
		this.source.enqueue(new PollResult.Failure("1"), new PollResult.Failure("2"),
				updates(30L), new PollResult.Failure("3"));
		TelegramUpdatePoller poller = poller();

		for (int cycle = 0; cycle < 4; cycle++) {
			poller.pollOnce();
		}

		assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(5)),
				this.sleeper.slept());
	}

	/**
	 * 장애 중에 로그·알림이 쏟아지면 그게 또 다른 장애다. 임계값 도달 시 1회, 복구 후 재도달 시 다시 1회.
	 */
	@Test
	void alertsOncePerOutage() {
		this.source.enqueueFailures(13);
		TelegramUpdatePoller poller = poller(3);

		for (int cycle = 0; cycle < 13; cycle++) {
			poller.pollOnce();
		}
		assertEquals(1, errorLogCount(), () -> "ERROR 로그: " + errorMessages());

		// 복구되면 카운터와 플래그가 함께 내려간다.
		this.source.enqueue(updates(40L));
		poller.pollOnce();
		assertEquals(1, errorLogCount());

		this.source.enqueueFailures(3);
		for (int cycle = 0; cycle < 3; cycle++) {
			poller.pollOnce();
		}
		assertEquals(2, errorLogCount(), () -> "ERROR 로그: " + errorMessages());
	}

	/** 임계값에 닿기 전에는 조용해야 한다. */
	@Test
	void staysQuietBelowTheAlertThreshold() {
		this.source.enqueueFailures(2);
		TelegramUpdatePoller poller = poller(3);

		poller.pollOnce();
		poller.pollOnce();

		assertEquals(0, errorLogCount(), () -> "ERROR 로그: " + errorMessages());
		assertEquals(2, this.sleeper.slept().size());
	}

	private TelegramUpdatePoller poller() {
		return poller(20);
	}

	private TelegramUpdatePoller poller(int alertThreshold) {
		return new TelegramUpdatePoller(this.source, this.handler,
				new PollingProperties(true, BACKOFF, MAX_BACKOFF, alertThreshold),
				new TelegramProperties(null, null, null, null), this.sleeper);
	}

	private static PollResult updates(long... updateIds) {
		List<TelegramUpdate> list = new ArrayList<>();
		for (long updateId : updateIds) {
			list.add(new TelegramUpdate(updateId, 1_000L + updateId, "/start"));
		}
		return new PollResult.Updates(list);
	}

	private long errorLogCount() {
		return this.appender.list.stream().filter((event) -> event.getLevel() == Level.ERROR).count();
	}

	private List<String> errorMessages() {
		return this.appender.list.stream()
				.filter((event) -> event.getLevel() == Level.ERROR)
				.map(ILoggingEvent::getFormattedMessage)
				.toList();
	}

	/** 대본대로 결과를 돌려주고, 요청받은 offset을 기록한다. 대본이 비면 "업데이트 없음"이다. */
	private static final class ScriptedUpdateSource implements UpdateSource {

		private final Deque<PollResult> scripted = new ArrayDeque<>();

		private final List<Long> requestedOffsets = new ArrayList<>();

		void enqueue(PollResult... results) {
			this.scripted.addAll(List.of(results));
		}

		void enqueueFailures(int count) {
			for (int index = 0; index < count; index++) {
				this.scripted.add(new PollResult.Failure("HTTP 500 #" + index));
			}
		}

		List<Long> requestedOffsets() {
			return List.copyOf(this.requestedOffsets);
		}

		@Override
		public PollResult getUpdates(long offset, int timeoutSeconds) {
			this.requestedOffsets.add(offset);
			PollResult next = this.scripted.poll();
			return (next != null) ? next : new PollResult.Updates(List.of());
		}
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

	/**
	 * 협력자가 필요 없는 스텁이다. {@code handle}이 예외를 삼키도록 만들어져 있어, 루프의 예외 내성을
	 * 검증하려면 던지는 핸들러가 따로 있어야 한다.
	 */
	private static final class RecordingHandler extends BotCommandHandler {

		private final List<Long> handledIds = new ArrayList<>();

		private Long failingUpdateId;

		RecordingHandler() {
			super(null, null, null);
		}

		void failOn(long updateId) {
			this.failingUpdateId = updateId;
		}

		List<Long> handledIds() {
			return List.copyOf(this.handledIds);
		}

		@Override
		public void handle(TelegramUpdate update) {
			this.handledIds.add(update.updateId());
			if (this.failingUpdateId != null && this.failingUpdateId == update.updateId()) {
				throw new IllegalStateException("처리 실패 " + update.updateId());
			}
		}
	}
}
