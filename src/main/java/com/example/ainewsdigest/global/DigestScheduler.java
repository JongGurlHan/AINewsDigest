package com.example.ainewsdigest.global;

import com.example.ainewsdigest.delivery.DigestSendService;
import com.example.ainewsdigest.delivery.DigestSendService.SendSummary;
import com.example.ainewsdigest.digest.DigestGenerationService;
import com.example.ainewsdigest.digest.DigestGenerationService.GenerationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 배치를 시각에 맞춰 돌리고, 결과를 보고 알림과 헬스체크 핑을 가른다.
 *
 * <p><b>도메인 로직을 두지 않는다.</b> 생성도 발송도 각자의 서비스가 이미 끝내 놓은 일이고, 여기는
 * 그 결과값({@link GenerationResult}, {@link SendSummary})으로 판정만 한다.
 *
 * <h2>{@code zone}을 절대 생략하지 마라</h2>
 * 운영 서버(Oracle Cloud VM)의 기본 타임존은 UTC다. {@code @Scheduled}에 존을 빼면 07:00 UTC =
 * 한국 시간 오후 4시에 돈다. 같은 이유로 날짜도 {@link #today()}를 거쳐 {@code Asia/Seoul} 기준으로만
 * 구한다 — {@code LocalDate.now()}를 인자 없이 부르면 07:00 KST 시점에 UTC로는 아직 전날이라
 * 생성과 발송이 서로 다른 날짜를 보고 매일 "다이제스트 없음"이 된다.
 *
 * <p>cron과 zone은 설정 키로 빼지 않는다. 어노테이션 리터럴을 읽는 코드만 있으면 되고, 발송 시각은
 * 제품 결정이라 바뀌면 재빌드해도 무방하다. 아무도 읽지 않는 설정 키를 만들지 않는다.
 *
 * <h2>세 시각</h2>
 * <table>
 *   <tr><td>07:00</td><td>생성</td><td>실패하면 failure 알림</td></tr>
 *   <tr><td>07:15</td><td>생성 재시도</td><td>복구되면 warning, 또 실패하면 failure</td></tr>
 *   <tr><td>07:30</td><td>발송</td><td>PENDING·EMPTY 모두 대상</td></tr>
 * </table>
 *
 * <p>07:15가 "30분의 복구 여유"를 실제로 쓰는 유일한 장치다. 재시도에 별도의 멱등성 검사를 만들지
 * 않는다 — {@code generate()}가 이미 그날 다이제스트 존재 여부로 막고 있어서, 정상일에는 조회 한 번으로
 * 즉시 끝나고 무해하다. 검사를 두 벌 두면 규칙이 갈라진다.
 *
 * <p>스케줄러 스레드 풀은 1이다({@code spring.task.scheduling.pool.size}). 생성이 길어져 07:30을 넘겨도
 * 발송은 동시 실행이 아니라 지연 실행된다 — 이게 옳다. 늘리면 생성 중에 발송이 시작돼 다이제스트를
 * 찾지 못하고 "다이제스트 없음" 오탐이 간다.
 */
@Component
@ConditionalOnProperty(prefix = "ainewsdigest.scheduler", name = "enabled", havingValue = "true",
		matchIfMissing = true)
public class DigestScheduler {

	/** 이 서비스의 모든 "오늘"은 이 존 기준이다. 서버 기본 타임존을 절대 쓰지 않는다. */
	static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);

	/** 시도한 구독자 중 이 비율 이상이 실패하면 경고한다. 경계값(정확히 30%)도 경고 대상이다. */
	private static final double FAILURE_RATE_THRESHOLD = 0.3;

	private final DigestGenerationService generation;

	private final DigestSendService sending;

	private final AdminNotifier notifier;

	private final HealthPinger pinger;

	private final Clock clock;

	/**
	 * 07:00 생성이 실패했는가. 07:15가 "복구됨"을 알릴지 판정하는 유일한 근거다.
	 *
	 * <p>{@link GenerationResult}로는 판정할 수 없다. 조용한 날 새로 만든 EMPTY와 "이미 있어서 그냥
	 * 돌아온" EMPTY가 둘 다 {@code (EMPTY, 0, 0, 0)}이라 구분되지 않는다.
	 *
	 * <p>풀 크기가 1이라 사실상 한 스레드가 만지지만, 스케줄러 스레드는 교체될 수 있으므로 가시성을
	 * 위해 {@code volatile}로 둔다.
	 */
	private volatile boolean generationFailed;

	public DigestScheduler(DigestGenerationService generation, DigestSendService sending, AdminNotifier notifier,
			HealthPinger pinger, Clock clock) {
		this.generation = generation;
		this.sending = sending;
		this.notifier = notifier;
		this.pinger = pinger;
		this.clock = clock;
	}

	/** 매일 07:00 KST. 주말·공휴일 구분 없이 돈다 (PRD 발송 규칙). */
	@Scheduled(cron = "0 0 7 * * *", zone = "Asia/Seoul")
	public void generateDaily() {
		LocalDate date = today();
		log.info("[07:00] {} 다이제스트 생성 시작", date);
		try {
			GenerationResult result = this.generation.generate(date);
			this.generationFailed = false;
			log.info("[07:00] {} 생성 종료: {}", date, result);
			inspect(date, result);
		}
		catch (RuntimeException ex) {
			this.generationFailed = true;
			log.error("[07:00] {} 다이제스트 생성 실패", date, ex);
			this.notifier.notifyFailure("다이제스트 생성 실패",
					date + " 생성 중 예외가 발생했다. 07:15 재시도가 남아 있다.\n" + ex);
		}
	}

	/**
	 * 매일 07:15 KST. {@link #generateDaily()}와 같은 메서드를 한 번 더 부르는 것이 전부다.
	 *
	 * <p>정상일에는 {@code generate()}가 그날 다이제스트를 보고 즉시 반환하므로 아무 알림도 나가지 않는다.
	 * 07:00이 실패했던 경우에만 복구 warning을 보낸다 — 관리자가 앞선 failure 알림을 보고 새벽에
	 * 일어나지 않도록.
	 */
	@Scheduled(cron = "0 15 7 * * *", zone = "Asia/Seoul")
	public void retryGenerate() {
		LocalDate date = today();
		boolean recovering = this.generationFailed;
		log.info("[07:15] {} 다이제스트 생성 재시도 (07:00 실패 여부={})", date, recovering);
		try {
			GenerationResult result = this.generation.generate(date);
			this.generationFailed = false;
			if (recovering) {
				this.notifier.notifyWarning("다이제스트 생성 복구됨",
						date + " 07:00 생성은 실패했지만 07:15 재시도가 성공했다. 07:30 발송은 예정대로 나간다.");
			}
			inspect(date, result);
		}
		catch (RuntimeException ex) {
			this.generationFailed = true;
			log.error("[07:15] {} 다이제스트 생성 재시도 실패", date, ex);
			this.notifier.notifyFailure("다이제스트 생성 재시도 실패",
					date + " 07:15 재시도도 실패했다. 07:30 발송도 실패한다.\n" + ex);
		}
	}

	/** 매일 07:30 KST. PENDING·EMPTY 둘 다 발송 대상이다 (ADR-014). */
	@Scheduled(cron = "0 30 7 * * *", zone = "Asia/Seoul")
	public void sendDaily() {
		LocalDate date = today();
		log.info("[07:30] {} 다이제스트 발송 시작", date);
		SendSummary summary;
		try {
			summary = this.sending.send(date);
		}
		catch (RuntimeException ex) {
			log.error("[07:30] {} 다이제스트 발송 실패", date, ex);
			this.notifier.notifyFailure("다이제스트 발송 실패", date + " 발송 중 예외가 발생했다.\n" + ex);
			return;
		}
		log.info("[07:30] {} 발송 종료: {}", date, summary);
		if (!summary.digestFound()) {
			this.notifier.notifyFailure("발송할 다이제스트가 없다",
					date + " 다이제스트가 저장되어 있지 않다. 07:00·07:15 생성이 모두 실패했다는 뜻이다.");
			return;
		}
		if (!summary.sentAtRecorded()) {
			// 전원 실패를 실패율 경고로 뭉뚱그리지 않는다. 그날 다이제스트는 미발송으로 남아 있다 (ADR-018).
			this.notifier.notifyFailure("다이제스트 발송 전원 실패",
					date + " 시도한 구독자 " + summary.totalSubscribers() + "명이 전원 실패했다. "
							+ "sent_at을 남기지 않았으므로 --ainewsdigest.run=send 로 재실행할 수 있다.");
			return;
		}
		warnOnHighFailureRate(date, summary);
		// 핑은 여기서만 나간다. 위 두 분기에서 보내면 아무도 못 받은 날에 외부 감시가 초록불이 된다.
		this.pinger.pingSuccess();
	}

	/**
	 * 수집 결과를 읽어 알림 등급을 가른다 (ARCHITECTURE "다이제스트 생성"의 판정 표).
	 *
	 * <p><b>후보 0건을 무조건 정상으로 처리하지 마라.</b> 소스가 전부 죽어도 후보는 0건이고, 그대로 두면
	 * EMPTY가 정상 발송되고 헬스체크 핑까지 나가 관리자도 외부 감시도 아무 이상을 느끼지 못한다.
	 * ADR-010이 막으려던 사각지대가 정확히 여기다. 가르는 축은 {@code failedSourceCount} 하나다.
	 */
	private void inspect(LocalDate date, GenerationResult result) {
		if (result.candidateCount() == 0) {
			if (result.failedSourceCount() > 0) {
				this.notifier.notifyFailure("수집 전면 실패 의심",
						date + " 후보가 0건인데 수집 소스 " + result.failedSourceCount() + "개가 실패했다. "
								+ "\"뉴스 없는 날\"이 아니라 수집 장애일 가능성이 높다.");
			}
			// 후보 0건 + 실패 소스 0건은 진짜 조용한 날이다. EMPTY 발송이 정상 동작이므로 알리지 않는다 (ADR-013).
			return;
		}
		if (result.failedSourceCount() > 0) {
			this.notifier.notifyWarning("일부 수집 소스 장애",
					date + " 수집 소스 " + result.failedSourceCount() + "개가 실패했다. "
							+ "후보 " + result.candidateCount() + "건으로 다이제스트는 만들어졌다.");
		}
	}

	/**
	 * 분모는 {@code totalSubscribers}(재개 스킵분 제외 = 실제로 시도한 수)다. 스킵분을 포함하면 재개
	 * 실행에서 실패율이 실제보다 낮게 계산돼 경고가 묻힌다.
	 */
	private void warnOnHighFailureRate(LocalDate date, SendSummary summary) {
		if (summary.totalSubscribers() == 0) {
			return;
		}
		double rate = (double) summary.failed() / summary.totalSubscribers();
		if (rate < FAILURE_RATE_THRESHOLD) {
			return;
		}
		this.notifier.notifyWarning("발송 실패율이 높다",
				date + " 시도 " + summary.totalSubscribers() + "명 중 " + summary.failed() + "명 실패 ("
						+ Math.round(rate * 100) + "%). 발송 자체는 완료로 기록됐다.");
	}

	/**
	 * 주입된 {@link Clock}의 존이 무엇이든 서울 기준 날짜를 돌려준다. 클럭 쪽 설정에 기대지 않고 여기서
	 * 한 번 더 못을 박는 이유는, 이 값이 틀리면 생성과 발송이 서로 다른 날짜를 보고 매일 조용히 어긋나기
	 * 때문이다 — 예외도 로그도 남지 않는 종류의 고장이다.
	 */
	private LocalDate today() {
		return LocalDate.now(this.clock.withZone(SEOUL));
	}
}
