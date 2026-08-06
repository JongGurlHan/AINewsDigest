package com.example.ainewsdigest.curation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * OpenAI 호출 설정.
 *
 * <p>{@code @Component}나 {@code @EnableConfigurationProperties}를 붙이지 않는다.
 * {@code AinewsdigestApplication}의 {@code @ConfigurationPropertiesScan}이 등록한다.
 *
 * <p><b>{@code apiKey}는 환경변수 {@code OPENAI_API_KEY}로만 주입한다.</b> 기본값을 실제 키로 채워
 * 커밋하면 그 순간 유출이다. 비어 있어도 기동은 되고 테스트는 전부 WireMock을 쓰므로 통과한다 —
 * 실제로 키가 없으면 호출 시점에 401이 나고 {@link CurationException}으로 올라간다.
 *
 * @param timeout      읽기 타임아웃. 요약 호출은 수십 초가 걸리므로 전역값(10s)을 쓰면 매일 아침 타임아웃이다 (ADR-015)
 * @param maxRetries   <b>재시도를 포함한 최대 시도 횟수.</b> 3이면 총 3번 호출하고 포기한다
 * @param retryBackoff 첫 재시도 대기 시간. 시도마다 2배로 늘린다
 */
@ConfigurationProperties("ainewsdigest.curation.openai")
public record OpenAiProperties(String baseUrl, String apiKey, String model, Duration timeout,
		Integer maxRetries, Duration retryBackoff) {

	private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
	private static final String DEFAULT_MODEL = "gpt-5-mini";
	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
	private static final int DEFAULT_MAX_RETRIES = 3;
	private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(1);

	public OpenAiProperties {
		baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
		apiKey = (apiKey == null) ? "" : apiKey;
		model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
		timeout = (timeout == null) ? DEFAULT_TIMEOUT : timeout;
		maxRetries = (maxRetries == null || maxRetries < 1) ? DEFAULT_MAX_RETRIES : maxRetries;
		retryBackoff = (retryBackoff == null) ? DEFAULT_RETRY_BACKOFF : retryBackoff;
	}
}
