package com.example.ainewsdigest.global;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

/**
 * 데드맨스위치 핑 (ADR-010의 뒤쪽 절반. 앞쪽은 {@link AdminNotifier}다).
 *
 * <h2>왜 성공을 알리는가</h2>
 * 실패 알림만으로는 <b>앱이 죽어서 알림조차 못 보내는</b> 상황을 잡지 못한다. 게다가 이 서비스는
 * "오늘의 AI 뉴스는 없습니다"를 정상 동작으로 설계했기 때문에 침묵과 장애가 구분되지 않는다.
 * 성공했을 때만 핑을 보내고 <b>그 부재를 외부가 감지하는</b> 역방향 감시라야 이 사각지대가 사라진다.
 *
 * <h2>발송이 끝난 뒤에만 부른다</h2>
 * 생성 단계에서 부르면 07:30 발송이 통째로 실패해도 외부에서는 정상으로 관측된다. 호출 지점은
 * {@link DigestScheduler#sendDaily()} 한 곳뿐이며, 전원 실패·다이제스트 없음 경로에서는 부르지 않는다.
 *
 * <h2>실패해도 조용히 끝낸다</h2>
 * 감시 대상(아침 발송)이 감시 도구(healthchecks.io) 때문에 실패하면 본말이 전도된다. 상태 코드로도
 * I/O 예외로도 밖으로 나가지 않는다.
 */
@Component
public class HealthPinger {

	private static final Logger log = LoggerFactory.getLogger(HealthPinger.class);

	/**
	 * 어댑터별 타임아웃 (ADR-015). 핑은 발송이 다 끝난 뒤 덤으로 하는 일이라 오래 붙잡을 이유가 없다 —
	 * 전역 기본값(10초)보다 짧게 잡아 감시 도구가 배치의 지연 원인이 되지 않게 한다.
	 */
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final RestClient restClient;

	private final String url;

	public HealthPinger(RestClient.Builder builder, ClientHttpRequestFactoryBuilder<?> factoryBuilder,
			HttpClientSettings defaults, OpsProperties ops) {
		this.restClient = builder
				.requestFactory(factoryBuilder.build(defaults.withReadTimeout(TIMEOUT)))
				.build();
		// baseUrl을 걸지 않는다. 설정값이 곧 완전한 URL이다.
		this.url = ops.healthcheckUrl();
	}

	/** 발송이 성공적으로 끝난 직후 한 번 부른다. */
	public void pingSuccess() {
		if (this.url.isBlank()) {
			log.debug("healthcheck-url이 비어 있어 핑을 보내지 않는다.");
			return;
		}
		try {
			this.restClient.get()
					// URI 템플릿으로 넘기지 않는다. 설정 URL에 중괄호가 섞이면 확장 대상으로 오인된다.
					.uri(URI.create(this.url))
					.retrieve()
					// 기본 핸들러는 4xx·5xx를 예외로 만든다. 핑의 실패는 우리가 다룰 일이 아니다.
					.onStatus((status) -> true, (request, response) -> {
					})
					.toBodilessEntity();
			log.info("헬스체크 핑 전송 완료");
		}
		catch (RuntimeException ex) {
			log.warn("헬스체크 핑 실패. 발송 자체에는 영향이 없다: {}", ex.toString());
		}
	}
}
