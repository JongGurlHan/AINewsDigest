package com.example.ainewsdigest.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 다이제스트 발송의 재시도 설정 (ARCHITECTURE "다이제스트 발송" 5단계).
 *
 * <p>{@code @Component}나 {@code @EnableConfigurationProperties}를 붙이지 않는다.
 * {@code AinewsdigestApplication}의 {@code @ConfigurationPropertiesScan}이 등록한다.
 *
 * <p>대기 시간을 설정으로 뺀 이유는 테스트다. 값이 코드에 박혀 있으면 재시도 분기를 검증하는 테스트가
 * 실제로 7초를 기다리게 된다. {@link Sleeper}까지 주입 가능하게 둔 것도 같은 이유다.
 *
 * @param maxRetries       재시도 가능한 실패({@code Failed(retryable=true)})에 대한 <b>총 시도 횟수</b>.
 *                         3이면 보내고, 실패하면 두 번 더 보내고 포기한다
 * @param rateLimitRetries 429를 받은 뒤 같은 구독자에게 다시 시도하는 횟수
 * @param maxRetryAfter    429의 {@code retry_after} 상한. <b>이 값을 넘는 대기 요청은 따르지 않는다</b> —
 *                         한 명 때문에 나머지 구독자의 발송이 몇 분씩 밀리느니 그 한 명을 실패로 남긴다
 * @param retryBackoff     재시도 가능한 실패의 지수 백오프 <b>시작값</b>. 1s면 1s, 2s, 4s로 늘어난다
 */
@ConfigurationProperties("ainewsdigest.delivery")
public record DeliveryProperties(Integer maxRetries, Integer rateLimitRetries, Duration maxRetryAfter,
		Duration retryBackoff) {

	private static final int DEFAULT_MAX_RETRIES = 3;

	private static final int DEFAULT_RATE_LIMIT_RETRIES = 2;

	private static final Duration DEFAULT_MAX_RETRY_AFTER = Duration.ofSeconds(60);

	private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(1);

	public DeliveryProperties {
		// 0이면 아무에게도 보내지 않는다는 뜻이 되므로 최소 1회는 보장한다.
		maxRetries = (maxRetries == null || maxRetries < 1) ? DEFAULT_MAX_RETRIES : maxRetries;
		rateLimitRetries = (rateLimitRetries == null || rateLimitRetries < 0)
				? DEFAULT_RATE_LIMIT_RETRIES : rateLimitRetries;
		// 0은 허용한다(대기 없음). 음수만 기본값으로 되돌린다.
		maxRetryAfter = nonNegativeOr(maxRetryAfter, DEFAULT_MAX_RETRY_AFTER);
		retryBackoff = nonNegativeOr(retryBackoff, DEFAULT_RETRY_BACKOFF);
	}

	private static Duration nonNegativeOr(Duration value, Duration fallback) {
		return (value == null || value.isNegative()) ? fallback : value;
	}
}
