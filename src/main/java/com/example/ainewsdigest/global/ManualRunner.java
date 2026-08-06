package com.example.ainewsdigest.global;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 생성·발송을 커맨드라인 인자로 한 번 돌리는 수동 트리거. 새벽에 배치가 실패했을 때 SSH로 복구하기
 * 위한 것이다.
 *
 * <pre>
 * java -jar app.jar --ainewsdigest.run=generate
 * java -jar app.jar --ainewsdigest.run=send
 * </pre>
 *
 * <p>인자가 없으면 아무것도 하지 않고 평소처럼 서버로 뜬다.
 *
 * <h2>HTTP 엔드포인트로 만들지 않는다</h2>
 * 이 앱에는 인증이 없다(Spring Security는 MVP 제외 사항이다). 배치를 트리거하는 엔드포인트를 열면
 * 공인 IP를 아는 누구나 발송과 LLM 호출을 유발할 수 있다 — 비용은 우리가 낸다.
 *
 * <h2>{@link DigestScheduler}를 거쳐 부른다</h2>
 * 생성·발송 서비스를 직접 부르지 않는다. 그러면 날짜 계산(Asia/Seoul)과 관리자 알림·헬스체크 판정이
 * 스케줄 실행과 수동 실행에서 갈라진다. 수동 복구야말로 결과를 알림으로 받아야 하는 상황이다.
 *
 * <p><b>운영 메모.</b> 이 실행도 웹 서버를 띄운 뒤에 돌기 때문에, 운영 인스턴스가 떠 있는 상태에서
 * 같은 머신에 실행하면 포트 충돌로 기동 자체가 실패한다. {@code --server.port=0}을 함께 준다.
 * 작업이 끝나도 프로세스는 살아 있으므로 확인 후 종료한다.
 */
@Component
public class ManualRunner implements ApplicationRunner {

	/** 스프링 프로퍼티로도 바인딩되지만 판정은 인자에서만 한다 — yml에 실수로 적어두고 매 기동마다 도는 것을 막는다. */
	static final String OPTION = "ainewsdigest.run";

	private static final String GENERATE = "generate";

	private static final String SEND = "send";

	private static final Logger log = LoggerFactory.getLogger(ManualRunner.class);

	/**
	 * 스케줄러는 {@code ainewsdigest.scheduler.enabled=false}면 존재하지 않는다. 그 배포에서 수동 실행을
	 * 시도했을 때 기동 실패가 아니라 명확한 로그로 끝나야 한다.
	 */
	private final ObjectProvider<DigestScheduler> scheduler;

	public ManualRunner(ObjectProvider<DigestScheduler> scheduler) {
		this.scheduler = scheduler;
	}

	@Override
	public void run(ApplicationArguments args) {
		List<String> values = args.getOptionValues(OPTION);
		if (values == null || values.isEmpty()) {
			return;
		}
		String job = values.getLast();
		DigestScheduler target = this.scheduler.getIfAvailable();
		if (target == null) {
			log.error("--{}={} 를 받았지만 스케줄러가 비활성(ainewsdigest.scheduler.enabled=false)이라 실행할 수 없다.",
					OPTION, job);
			return;
		}
		log.info("수동 실행: --{}={}", OPTION, job);
		switch (job) {
			case GENERATE -> target.generateDaily();
			case SEND -> target.sendDaily();
			default -> log.error("--{} 값이 잘못됐다: {} (가능한 값: {}, {})", OPTION, job, GENERATE, SEND);
		}
	}
}
