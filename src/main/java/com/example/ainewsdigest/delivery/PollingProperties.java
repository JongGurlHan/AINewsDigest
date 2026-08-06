package com.example.ainewsdigest.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 롱폴링 루프 설정 (ADR-008).
 *
 * <p>{@code @Component}나 {@code @EnableConfigurationProperties}를 붙이지 않는다.
 * {@code AinewsdigestApplication}의 {@code @ConfigurationPropertiesScan}이 등록한다.
 *
 * @param enabled        폴러 기동 여부. <b>테스트 설정에서는 false다</b> — 백그라운드 스레드가 실제
 *                       텔레그램 API를 때리면 테스트가 불안정해진다. 실제 판정은
 *                       {@code TelegramUpdatePoller}의 {@code @ConditionalOnProperty}가 한다
 * @param failureBackoff 지수 백오프의 <b>시작값</b>. {@code PollResult.Failure}일 때만 적용된다 —
 *                       빈 {@code Updates}는 롱폴링의 가장 흔한 정상 응답이라 쉬지 않는다
 * @param maxBackoff     백오프 상한. 없으면 장애가 길어질수록 재시도 간격이 무한히 벌어진다
 * @param alertThreshold 연속 실패가 이 횟수에 도달하면 ERROR 로그를 <b>1회만</b> 남긴다.
 *                       기본 20회는 30초 폴링 기준 약 10분이다
 */
@ConfigurationProperties("ainewsdigest.telegram.polling")
public record PollingProperties(Boolean enabled, Duration failureBackoff, Duration maxBackoff,
		Integer alertThreshold) {

	private static final Duration DEFAULT_FAILURE_BACKOFF = Duration.ofSeconds(5);

	private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(60);

	private static final int DEFAULT_ALERT_THRESHOLD = 20;

	public PollingProperties {
		enabled = (enabled == null) || enabled;
		failureBackoff = positiveOr(failureBackoff, DEFAULT_FAILURE_BACKOFF);
		maxBackoff = positiveOr(maxBackoff, DEFAULT_MAX_BACKOFF);
		// 상한이 시작값보다 작으면 지수 백오프가 첫 회부터 잘린다. 설정 실수를 조용히 따르지 않는다.
		maxBackoff = (maxBackoff.compareTo(failureBackoff) < 0) ? failureBackoff : maxBackoff;
		alertThreshold = (alertThreshold == null || alertThreshold < 1) ? DEFAULT_ALERT_THRESHOLD : alertThreshold;
	}

	private static Duration positiveOr(Duration value, Duration fallback) {
		return (value == null || value.isNegative() || value.isZero()) ? fallback : value;
	}
}
