package com.example.ainewsdigest.curation;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 호출 (ADR-002). Codex CLI를 셸 아웃하지 않는다 — 새벽 배치에서
 * 토큰 재인증·자유 형식 출력 파싱·수 분의 실행 시간이 전부 실패 지점이 된다.
 *
 * <p>응답 구조는 {@code response_format}의 JSON Schema로 강제한다. 그래서 호출자는 반환된 문자열을
 * Jackson으로 바로 파싱하면 된다 — <b>정규식으로 긁지 마라.</b>
 *
 * <p>포트({@link ArticleSelector}·{@link ArticleSummarizer})가 아니라 그 구현체들이 공유하는 저수준
 * 클라이언트다. 인터페이스를 두지 않는 이유는 선별기·요약기가 이미 인터페이스 뒤에 있어서, 테스트가
 * 그 지점에서 페이크로 갈아끼워지기 때문이다.
 */
@Component
public class OpenAiClient {

	private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

	/** 스키마 이름은 OpenAI에 전달되는 식별자일 뿐 응답 구조에 영향을 주지 않는다. */
	private static final String SCHEMA_NAME = "curation_result";

	private final RestClient restClient;

	private final OpenAiProperties properties;

	/**
	 * 타임아웃을 <b>생성자에서 지정한다</b> (ADR-015). 전역 {@code spring.http.client.read-timeout}은
	 * 수집용 10초로 잡혀 있고, 그대로 두면 요약 호출이 매일 아침 타임아웃난다. step 2의 수집 어댑터와
	 * 같은 방식이되 값만 60초로 다르다.
	 */
	public OpenAiClient(RestClient.Builder builder,
			ClientHttpRequestFactoryBuilder<?> factoryBuilder,
			HttpClientSettings defaults,
			OpenAiProperties properties) {
		this.restClient = builder
				.baseUrl(properties.baseUrl())
				.requestFactory(factoryBuilder.build(defaults.withReadTimeout(properties.timeout())))
				.build();
		this.properties = properties;
	}

	/**
	 * JSON Schema로 구조를 강제한 Chat Completions 호출. 모델이 돌려준 JSON 문자열을 그대로 반환한다.
	 *
	 * <p>재시도 정책:
	 * <ul>
	 *   <li>429·5xx·I/O 실패 → 지수 백오프로 재시도 (총 {@code maxRetries}회 시도)</li>
	 *   <li>그 외 4xx → <b>재시도하지 않는다.</b> 요청 자체가 잘못된 것이라 다시 보내도 같은 답이 온다.
	 *       키가 틀렸는데 3번 더 물어보는 것은 시간 낭비이고, 07:15 재시도 여유만 깎아먹는다</li>
	 * </ul>
	 *
	 * @param jsonSchema 응답 JSON Schema. {@code strict: true}로 감싸므로 모든 객체에
	 *                   {@code additionalProperties: false}와 전체 {@code required}가 있어야 한다
	 * @throws CurationException 모든 시도가 실패했거나 응답을 해석할 수 없을 때
	 */
	public String completeAsJson(String systemPrompt, String userPrompt, Map<String, Object> jsonSchema) {
		Map<String, Object> body = requestBody(systemPrompt, userPrompt, jsonSchema);
		RuntimeException lastFailure = null;
		for (int attempt = 1; attempt <= properties.maxRetries(); attempt++) {
			try {
				return content(post(body));
			}
			catch (RestClientResponseException ex) {
				if (!isRetryable(ex)) {
					throw new CurationException("OpenAI가 요청을 거절했다 (status=%d, 재시도하지 않는다): %s"
							.formatted(ex.getStatusCode().value(), ex.getResponseBodyAsString()), ex);
				}
				lastFailure = ex;
			}
			catch (ResourceAccessException ex) {
				// 타임아웃·연결 실패. 다음 시도에서 살아날 수 있다.
				lastFailure = ex;
			}
			log.warn("OpenAI 호출 실패 ({}/{}회): {}", attempt, properties.maxRetries(), lastFailure.toString());
			if (attempt < properties.maxRetries()) {
				sleep(backoff(attempt));
			}
		}
		throw new CurationException("OpenAI 호출이 %d회 모두 실패했다".formatted(properties.maxRetries()), lastFailure);
	}

	private static boolean isRetryable(RestClientResponseException ex) {
		return ex.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()
				|| ex.getStatusCode().is5xxServerError();
	}

	private ChatResponse post(Map<String, Object> body) {
		return restClient.post()
				.uri("/chat/completions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(ChatResponse.class);
	}

	private Map<String, Object> requestBody(String systemPrompt, String userPrompt, Map<String, Object> jsonSchema) {
		return Map.of(
				"model", properties.model(),
				"messages", List.of(
						Map.of("role", "system", "content", systemPrompt),
						Map.of("role", "user", "content", userPrompt)),
				"response_format", Map.of(
						"type", "json_schema",
						"json_schema", Map.of(
								"name", SCHEMA_NAME,
								// strict가 빠지면 스키마는 참고 사항이 되고 모델이 형식을 어겨도 200이 온다.
								"strict", true,
								"schema", jsonSchema)));
	}

	/**
	 * 여기서 던지는 {@link CurationException}은 재시도 대상이 아니다. 응답이 왔는데 쓸 수 없는 형태라면
	 * 같은 요청을 다시 보내도 대개 같은 결과가 온다.
	 */
	private static String content(ChatResponse response) {
		if (response == null || response.choices() == null || response.choices().isEmpty()) {
			throw new CurationException("OpenAI 응답에 choices가 없다");
		}
		Choice choice = response.choices().get(0);
		if (choice.message() == null) {
			throw new CurationException("OpenAI 응답에 message가 없다");
		}
		String refusal = choice.message().refusal();
		if (refusal != null && !refusal.isBlank()) {
			throw new CurationException("OpenAI가 응답을 거부했다: " + refusal);
		}
		// 토큰 한도로 잘린 응답은 JSON이 깨져 있다. 파싱 예외로 넘기면 원인이 보이지 않는다.
		if ("length".equals(choice.finishReason())) {
			throw new CurationException("OpenAI 응답이 토큰 한도에 걸려 잘렸다");
		}
		String content = choice.message().content();
		if (content == null || content.isBlank()) {
			throw new CurationException("OpenAI 응답 본문이 비어 있다 (finishReason=%s)".formatted(choice.finishReason()));
		}
		return content;
	}

	private Duration backoff(int attempt) {
		return properties.retryBackoff().multipliedBy(1L << (attempt - 1));
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new CurationException("OpenAI 재시도 대기 중 인터럽트됐다", ex);
		}
	}

	record ChatResponse(List<Choice> choices) {
	}

	record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {
	}

	record Message(String content, String refusal) {
	}
}
