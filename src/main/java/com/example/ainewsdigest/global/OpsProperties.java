package com.example.ainewsdigest.global;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 운영 감시 설정 (ADR-010).
 *
 * <p>{@code @Component}나 {@code @EnableConfigurationProperties}를 붙이지 않는다.
 * {@code AinewsdigestApplication}의 {@code @ConfigurationPropertiesScan}이 등록한다.
 *
 * @param healthcheckUrl 발송 성공 시 GET 할 데드맨스위치 URL (healthchecks.io 형식). <b>비어 있는 것이
 *                       정상 상태다</b> — 감시를 붙이지 않은 로컬·테스트 환경에서 기동이 막히면 안 된다.
 *                       판정은 {@link HealthPinger}가 한다
 */
@ConfigurationProperties("ainewsdigest.ops")
public record OpsProperties(String healthcheckUrl) {

	public OpsProperties {
		healthcheckUrl = (healthcheckUrl == null) ? "" : healthcheckUrl.trim();
	}
}
