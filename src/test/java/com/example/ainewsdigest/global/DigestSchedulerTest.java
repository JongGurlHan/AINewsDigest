package com.example.ainewsdigest.global;

import com.example.ainewsdigest.delivery.DigestSendService;
import com.example.ainewsdigest.delivery.DigestSendService.SendSummary;
import com.example.ainewsdigest.digest.DigestGenerationService;
import com.example.ainewsdigest.digest.DigestGenerationService.GenerationResult;
import com.example.ainewsdigest.digest.DigestStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 스케줄러는 <b>판정만</b> 한다 — 생성·발송 서비스를 부르고 그 결과로 알림과 핑을 가른다. 그래서
 * 컨텍스트 없이 스텁 네 개로 전부 검증된다. {@code @SpringBootTest}로 올리면 컨테이너가 뜨는 대신
 * 검증되는 것은 늘지 않는다.
 *
 * <p>시각은 고정 {@link Clock}으로만 다룬다. {@code LocalDate.now()}를 인자 없이 부르면 서버 기본
 * 타임존(운영은 UTC)이 쓰여 07:00 KST에는 아직 전날이고, 생성과 발송이 서로 다른 날짜를 보게 된다.
 * 아래 클럭은 <b>UTC 존</b>으로 만든다 — 스케줄러가 스스로 Asia/Seoul로 바꾸는지를 봐야 하므로
 * 클럭 쪽에 서울을 심어두면 그 변환이 있는지 없는지 알 수 없다.
 */
class DigestSchedulerTest {

	/** 2026-08-05T22:30Z = 2026-08-06T07:30+09:00. UTC로 읽으면 하루가 어긋난다. */
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-05T22:30:00Z"), ZoneOffset.UTC);

	private static final LocalDate SEOUL_TODAY = LocalDate.of(2026, 8, 6);

	private final StubGeneration generation = new StubGeneration();

	private final StubSend sending = new StubSend();

	private final RecordingNotifier notifier = new RecordingNotifier();

	private final RecordingPinger pinger = new RecordingPinger();

	// --- 날짜 (금지사항: LocalDate.now() 무인자 호출) ---

	@Test
	void generateDailyUsesTheSeoulDate() {
		this.generation.returning(pending(5, 12, 0));

		scheduler().generateDaily();

		assertEquals(List.of(SEOUL_TODAY), this.generation.dates());
	}

	/** 재시도가 다른 날짜를 보면 07:00이 만든 다이제스트를 못 찾고 하루치를 하나 더 만든다. */
	@Test
	void retryGenerateUsesTheSeoulDateToo() {
		this.generation.returning(pending(5, 12, 0));

		scheduler().retryGenerate();

		assertEquals(List.of(SEOUL_TODAY), this.generation.dates());
	}

	@Test
	void sendDailyUsesTheSeoulDate() {
		this.sending.returning(summary(10, 10, 0, true));

		scheduler().sendDaily();

		assertEquals(List.of(SEOUL_TODAY), this.sending.dates());
	}

	// --- 생성 (07:00) ---

	@Test
	void generationExceptionNotifiesFailure() {
		this.generation.throwing(new IllegalStateException("OpenAI 500"));

		scheduler().generateDaily();

		assertEquals(List.of("failure"), this.notifier.levels());
		assertTrue(this.notifier.notes().getFirst().detail().contains("OpenAI 500"),
				() -> "원인이 알림에 없다: " + this.notifier.notes());
	}

	/** 소스가 전부 죽어도 후보는 0건이다. 조용한 날과 구분하지 않으면 EMPTY가 정상 발송되고 핑까지 나간다. */
	@Test
	void collectionWideFailureNotifiesFailure() {
		this.generation.returning(new GenerationResult(DigestStatus.EMPTY, 0, 0, 3));

		scheduler().generateDaily();

		assertEquals(List.of("failure"), this.notifier.levels());
	}

	/** 진짜 뉴스가 없는 날이다. ADR-013이 정상 동작으로 설계한 경로라 알리지 않는다. */
	@Test
	void quietDayNotifiesNothing() {
		this.generation.returning(new GenerationResult(DigestStatus.EMPTY, 0, 0, 0));

		scheduler().generateDaily();

		assertEquals(List.of(), this.notifier.levels());
	}

	@Test
	void partialSourceFailureNotifiesWarning() {
		this.generation.returning(pending(4, 9, 1));

		scheduler().generateDaily();

		assertEquals(List.of("warning"), this.notifier.levels());
	}

	// --- 생성 재시도 (07:15) ---

	/**
	 * 정상일의 07:15다. {@code generate()}가 이미 있는 다이제스트를 보고 즉시 반환하므로
	 * 후보 0건·실패 소스 0건이 오는데, 여기서 알리면 매일 아침 오탐이 하나씩 쌓인다.
	 */
	@Test
	void retryStaysQuietWhenTheDigestAlreadyExists() {
		this.generation.returning(new GenerationResult(DigestStatus.PENDING, 0, 0, 0));

		DigestScheduler scheduler = scheduler();
		scheduler.generateDaily();
		scheduler.retryGenerate();

		assertEquals(2, this.generation.dates().size(), "재시도는 같은 메서드를 한 번 더 부르는 것으로 끝난다");
		assertEquals(List.of(), this.notifier.levels());
	}

	/** 앞선 failure를 보고 새벽에 일어나지 않도록, 복구됐다는 사실을 반드시 알린다. */
	@Test
	void retryAfterAFailedGenerationNotifiesRecovery() {
		this.generation.throwing(new IllegalStateException("OpenAI 500")).thenReturning(pending(4, 11, 0));

		DigestScheduler scheduler = scheduler();
		scheduler.generateDaily();
		scheduler.retryGenerate();

		assertEquals(List.of("failure", "warning"), this.notifier.levels());
		assertTrue(this.notifier.notes().getLast().title().contains("복구"),
				() -> "복구 알림이 아니다: " + this.notifier.notes().getLast());
	}

	/** 07:15도 실패하면 07:30 발송도 실패할 것이 확정이다. */
	@Test
	void retryFailureNotifiesFailureAgain() {
		this.generation.throwing(new IllegalStateException("OpenAI 500"))
				.thenThrowing(new IllegalStateException("OpenAI 503"));

		DigestScheduler scheduler = scheduler();
		scheduler.generateDaily();
		scheduler.retryGenerate();

		assertEquals(List.of("failure", "failure"), this.notifier.levels());
		assertTrue(this.notifier.notes().getLast().detail().contains("OpenAI 503"));
	}

	/** 복구 알림은 장애 한 번에 한 번이다. 플래그가 내려가지 않으면 다음 날 07:15도 "복구됨"을 보낸다. */
	@Test
	void recoveryIsNotifiedOncePerOutage() {
		this.generation.throwing(new IllegalStateException("OpenAI 500")).thenReturning(pending(4, 11, 0));

		DigestScheduler scheduler = scheduler();
		scheduler.generateDaily();
		scheduler.retryGenerate();
		scheduler.retryGenerate();

		assertEquals(List.of("failure", "warning"), this.notifier.levels());
	}

	// --- 발송 (07:30) ---

	@Test
	void pingsAfterASuccessfulSend() {
		this.sending.returning(summary(10, 10, 0, true));

		scheduler().sendDaily();

		assertEquals(1, this.pinger.pings());
		assertEquals(List.of(), this.notifier.levels());
	}

	/** 생성이 07:00·07:15 모두 실패했다는 뜻이다. */
	@Test
	void missingDigestNotifiesFailureAndSkipsThePing() {
		this.sending.returning(new SendSummary(false, 0, 0, 0, 0, false));

		scheduler().sendDaily();

		assertEquals(List.of("failure"), this.notifier.levels());
		assertEquals(0, this.pinger.pings());
	}

	/**
	 * {@code sentAtRecorded == false}는 아무도 받지 못했고 다이제스트가 재실행을 기다린다는 뜻이다
	 * (ADR-018). 실패율 경고와 같은 등급으로 묻으면 안 된다.
	 */
	@Test
	void allDeliveriesFailedNotifiesFailure() {
		this.sending.returning(summary(10, 0, 10, false));

		scheduler().sendDaily();

		assertEquals(List.of("failure"), this.notifier.levels());
	}

	/** 핑이 나가면 외부 감시가 초록불이 된다. 아무도 못 받은 날에 그러면 감시가 통째로 무의미해진다. */
	@Test
	void doesNotPingWhenEveryDeliveryFailed() {
		this.sending.returning(summary(10, 0, 10, false));

		scheduler().sendDaily();

		assertEquals(0, this.pinger.pings());
	}

	@Test
	void sendExceptionNotifiesFailureAndSkipsThePing() {
		this.sending.throwing(new IllegalStateException("DB 커넥션 없음"));

		scheduler().sendDaily();

		assertEquals(List.of("failure"), this.notifier.levels());
		assertEquals(0, this.pinger.pings());
	}

	/** 3/10 = 정확히 임계값. 경계에서 조용하면 "30% 이상"이라는 규칙이 사실상 31%가 된다. */
	@Test
	void highFailureRateNotifiesWarningButStillPings() {
		this.sending.returning(summary(10, 7, 3, true));

		scheduler().sendDaily();

		assertEquals(List.of("warning"), this.notifier.levels());
		assertEquals(1, this.pinger.pings());
	}

	@Test
	void lowFailureRateStaysQuiet() {
		this.sending.returning(summary(10, 8, 2, true));

		scheduler().sendDaily();

		assertEquals(List.of(), this.notifier.levels());
		assertEquals(1, this.pinger.pings());
	}

	/** 활성 구독자가 0명인 날. 0으로 나누지 않고, 발송은 정상 종료다 (ADR-018의 "시도 대상 0명"). */
	@Test
	void noSubscribersIsNotAFailure() {
		this.sending.returning(summary(0, 0, 0, true));

		scheduler().sendDaily();

		assertEquals(List.of(), this.notifier.levels());
		assertEquals(1, this.pinger.pings());
	}

	private DigestScheduler scheduler() {
		return new DigestScheduler(this.generation, this.sending, this.notifier, this.pinger, CLOCK);
	}

	private static GenerationResult pending(int itemCount, int candidateCount, int failedSourceCount) {
		return new GenerationResult(DigestStatus.PENDING, itemCount, candidateCount, failedSourceCount);
	}

	private static SendSummary summary(int total, int succeeded, int failed, boolean sentAtRecorded) {
		return new SendSummary(true, total, 0, succeeded, failed, sentAtRecorded);
	}

	/**
	 * 생성 서비스는 인터페이스가 아니다 (아웃바운드 포트가 아니라 오케스트레이션이라 추상화하지 않는다).
	 * 협력자 자리에 {@code null}을 넣고 {@code generate}만 덮는다 — 생성자는 필드 대입뿐이라 안전하다.
	 */
	private static final class StubGeneration extends DigestGenerationService {

		private final List<LocalDate> dates = new ArrayList<>();

		private final Deque<Object> script = new ArrayDeque<>();

		StubGeneration() {
			super(List.of(), null, null, null, null, null, null);
		}

		StubGeneration returning(GenerationResult result) {
			this.script.add(result);
			return this;
		}

		StubGeneration throwing(RuntimeException ex) {
			this.script.add(ex);
			return this;
		}

		StubGeneration thenReturning(GenerationResult result) {
			return returning(result);
		}

		StubGeneration thenThrowing(RuntimeException ex) {
			return throwing(ex);
		}

		List<LocalDate> dates() {
			return List.copyOf(this.dates);
		}

		@Override
		public GenerationResult generate(LocalDate date) {
			this.dates.add(date);
			// 대본이 하나뿐이면 계속 그 값을 쓴다. 07:15 재시도를 별도로 적지 않아도 되게.
			Object next = (this.script.size() > 1) ? this.script.poll() : this.script.peek();
			if (next instanceof RuntimeException ex) {
				throw ex;
			}
			return (next != null) ? (GenerationResult) next
					: new GenerationResult(DigestStatus.PENDING, 0, 0, 0);
		}
	}

	private static final class StubSend extends DigestSendService {

		private final List<LocalDate> dates = new ArrayList<>();

		private SendSummary result;

		private RuntimeException failure;

		StubSend() {
			super(null, null, null, null, null, null, null);
		}

		StubSend returning(SendSummary result) {
			this.result = result;
			return this;
		}

		StubSend throwing(RuntimeException ex) {
			this.failure = ex;
			return this;
		}

		List<LocalDate> dates() {
			return List.copyOf(this.dates);
		}

		@Override
		public SendSummary send(LocalDate date) {
			this.dates.add(date);
			if (this.failure != null) {
				throw this.failure;
			}
			return this.result;
		}
	}

	/** 알림의 <b>등급</b>이 이 클래스의 계약이다. 경고와 실패가 뒤바뀌면 아무도 못 받은 날이 묻힌다. */
	static final class RecordingNotifier extends AdminNotifier {

		private final List<Note> notes = new ArrayList<>();

		RecordingNotifier() {
			super(null, null);
		}

		List<Note> notes() {
			return List.copyOf(this.notes);
		}

		List<String> levels() {
			return this.notes.stream().map(Note::level).toList();
		}

		@Override
		public void notifyFailure(String title, String detail) {
			this.notes.add(new Note("failure", title, detail));
		}

		@Override
		public void notifyWarning(String title, String detail) {
			this.notes.add(new Note("warning", title, detail));
		}

		record Note(String level, String title, String detail) {
		}
	}

	private static final class RecordingPinger extends HealthPinger {

		private int pings;

		RecordingPinger() {
			super(RestClient.builder(), ClientHttpRequestFactoryBuilder.detect(), HttpClientSettings.defaults(),
					new OpsProperties(null));
		}

		int pings() {
			return this.pings;
		}

		@Override
		public void pingSuccess() {
			this.pings++;
		}
	}
}
