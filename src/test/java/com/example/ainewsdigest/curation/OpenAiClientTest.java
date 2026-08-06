package com.example.ainewsdigest.curation;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 OpenAI를 호출하지 않는다. 비용이 발생하고 CI가 API 키와 외부 상태에 종속된다.
 */
class OpenAiClientTest {

	private static final String CHAT_PATH = "/v1/chat/completions";

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	/** 재시도 대기를 실제로 기다리지 않는다. 백오프 로직 자체는 이 값의 배수로 검증된다. */
	private static final Duration FAST_BACKOFF = Duration.ofMillis(10);

	private static final Map<String, Object> SCHEMA = Map.of(
			"type", "object",
			"properties", Map.of("ok", Map.of("type", "boolean")),
			"required", List.of("ok"),
			"additionalProperties", false);

	/**
	 * {@code http2PlainDisabled}: 평문 HTTP에서 JDK HttpClient가 h2c 업그레이드를 시도하면 POST 본문이
	 * 유실된다. 운영에서 OpenAI는 TLS + ALPN으로 붙으므로 이 경로를 타지 않는다 — 스텁 서버만 HTTP/1.1로 고정한다.
	 */
	@RegisterExtension
	static final WireMockExtension wireMock = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort().http2PlainDisabled(true))
			.build();

	@Test
	void forcesResponseStructureWithJsonSchema() {
		stubOk();

		client().completeAsJson("system", "user", SCHEMA);

		Map<String, Object> body = lastRequestBody();
		Map<String, Object> responseFormat = section(body, "response_format");
		assertEquals("json_schema", responseFormat.get("type"));
		Map<String, Object> jsonSchema = section(responseFormat, "json_schema");
		// strict가 빠지면 스키마는 참고 사항이 되고 모델이 형식을 어겨도 200이 온다.
		assertEquals(Boolean.TRUE, jsonSchema.get("strict"));
		assertEquals(SCHEMA, jsonSchema.get("schema"));
	}

	@Test
	void sendsSystemAndUserPromptsWithConfiguredModel() {
		stubOk();

		client().completeAsJson("시스템 프롬프트", "유저 프롬프트", SCHEMA);

		Map<String, Object> body = lastRequestBody();
		assertEquals("gpt-5-mini", body.get("model"));
		List<Map<String, Object>> messages = messages(body);
		assertEquals("system", messages.get(0).get("role"));
		assertEquals("시스템 프롬프트", messages.get(0).get("content"));
		assertEquals("user", messages.get(1).get("role"));
		assertEquals("유저 프롬프트", messages.get(1).get("content"));
	}

	@Test
	void sendsApiKeyAsBearerToken() {
		stubOk();

		client().completeAsJson("system", "user", SCHEMA);

		wireMock.verify(postRequestedFor(urlEqualTo(CHAT_PATH))
				.withHeader("Authorization", equalTo("Bearer test-key")));
	}

	@Test
	void returnsTheModelContentAsIs() {
		String content = "{\"results\":[{\"index\":0}]}";
		wireMock.stubFor(post(urlEqualTo(CHAT_PATH)).willReturn(okJson(chatResponse(content))));

		assertEquals(content, client().completeAsJson("system", "user", SCHEMA));
	}

	/** 429는 잠시 뒤 살아난다. 여기서 포기하면 아침 배치가 통째로 날아간다. */
	@Test
	void retriesAfterTooManyRequests() {
		wireMock.stubFor(post(urlEqualTo(CHAT_PATH)).inScenario("429")
				.whenScenarioStateIs(STARTED)
				.willReturn(aResponse().withStatus(429))
				.willSetStateTo("recovered"));
		wireMock.stubFor(post(urlEqualTo(CHAT_PATH)).inScenario("429")
				.whenScenarioStateIs("recovered")
				.willReturn(okJson(chatResponse("{\"ok\":true}"))));

		assertEquals("{\"ok\":true}", client().completeAsJson("system", "user", SCHEMA));

		wireMock.verify(2, postRequestedFor(urlEqualTo(CHAT_PATH)));
	}

	/**
	 * 400은 요청 자체가 잘못된 것이라 다시 보내도 같은 답이 온다. 재시도하면 시간만 버리고
	 * 07:15 재시도 여유를 깎아먹는다.
	 */
	@Test
	void doesNotRetryOnBadRequest() {
		wireMock.stubFor(post(urlEqualTo(CHAT_PATH))
				.willReturn(badRequest().withBody("{\"error\":{\"message\":\"invalid schema\"}}")));

		CurationException ex = assertThrows(CurationException.class,
				() -> client().completeAsJson("system", "user", SCHEMA));

		assertTrue(ex.getMessage().contains("400"), ex.getMessage());
		wireMock.verify(1, postRequestedFor(urlEqualTo(CHAT_PATH)));
	}

	/** 401도 마찬가지다 — 키가 틀렸는데 3번 더 물어볼 이유가 없다. */
	@Test
	void doesNotRetryOnUnauthorized() {
		wireMock.stubFor(post(urlEqualTo(CHAT_PATH)).willReturn(aResponse().withStatus(401)));

		assertThrows(CurationException.class, () -> client().completeAsJson("system", "user", SCHEMA));

		wireMock.verify(1, postRequestedFor(urlEqualTo(CHAT_PATH)));
	}

	@Test
	void throwsAfterMaxAttemptsOnServerError() {
		wireMock.stubFor(post(urlEqualTo(CHAT_PATH)).willReturn(serverError()));

		assertThrows(CurationException.class, () -> client().completeAsJson("system", "user", SCHEMA));

		wireMock.verify(3, postRequestedFor(urlEqualTo(CHAT_PATH)));
	}

	/**
	 * 전역 {@code spring.http.client.read-timeout}은 수집용 10초다. 그 값이 그대로 쓰이면 요약 호출이
	 * 매일 아침 타임아웃난다 (ADR-015). 여기서는 전역 기본값을 100ms로 좁혀두고, 설정된 timeout이
	 * 그것을 덮어쓰는지 본다 — 60초를 실제로 기다리지 않고 같은 경로를 검증하기 위해서다.
	 */
	@Test
	void usesConfiguredTimeoutInsteadOfGlobalDefault() {
		wireMock.stubFor(post(urlEqualTo(CHAT_PATH))
				.willReturn(okJson(chatResponse("{\"ok\":true}")).withFixedDelay(400)));

		OpenAiClient client = new OpenAiClient(RestClient.builder(), ClientHttpRequestFactoryBuilder.detect(),
				HttpClientSettings.defaults().withReadTimeout(Duration.ofMillis(100)),
				properties(Duration.ofSeconds(5), 1));

		assertEquals("{\"ok\":true}", client.completeAsJson("system", "user", SCHEMA));
	}

	/** 반대 방향. 설정값이 실제로 적용되는지 본다 — 그냥 타임아웃이 없는 것과 구분되어야 한다. */
	@Test
	void configuredTimeoutIsActuallyApplied() {
		wireMock.stubFor(post(urlEqualTo(CHAT_PATH))
				.willReturn(okJson(chatResponse("{\"ok\":true}")).withFixedDelay(3000)));

		OpenAiClient client = new OpenAiClient(RestClient.builder(), ClientHttpRequestFactoryBuilder.detect(),
				HttpClientSettings.defaults().withReadTimeout(Duration.ofSeconds(30)),
				properties(Duration.ofMillis(200), 1));

		assertThrows(CurationException.class, () -> client.completeAsJson("system", "user", SCHEMA));
	}

	@Test
	void defaultsToSixtySecondTimeout() {
		OpenAiProperties defaults = new OpenAiProperties(null, null, null, null, null, null);

		assertEquals(Duration.ofSeconds(60), defaults.timeout());
		assertEquals("https://api.openai.com/v1", defaults.baseUrl());
		assertEquals("gpt-5-mini", defaults.model());
		assertEquals(3, defaults.maxRetries());
		// 키를 코드에 두지 않는다. 비어 있어도 기동은 되어야 한다.
		assertEquals("", defaults.apiKey());
	}

	/** 잘린 응답은 JSON이 깨져 있다. 파싱 예외로 넘기면 원인이 보이지 않는다. */
	@Test
	void failsWhenResponseWasTruncatedByTokenLimit() {
		wireMock.stubFor(post(urlEqualTo(CHAT_PATH)).willReturn(okJson("""
				{"choices":[{"finish_reason":"length","message":{"content":"{\\"results\\":[{\\"ind"}}]}
				""")));

		CurationException ex = assertThrows(CurationException.class,
				() -> client().completeAsJson("system", "user", SCHEMA));

		assertTrue(ex.getMessage().contains("잘렸다"), ex.getMessage());
		// 같은 요청을 다시 보내도 같은 결과다. 재시도하지 않는다.
		wireMock.verify(1, postRequestedFor(urlEqualTo(CHAT_PATH)));
	}

	@Test
	void failsWhenModelRefuses() {
		wireMock.stubFor(post(urlEqualTo(CHAT_PATH)).willReturn(okJson("""
				{"choices":[{"finish_reason":"stop","message":{"content":null,"refusal":"거부합니다"}}]}
				""")));

		CurationException ex = assertThrows(CurationException.class,
				() -> client().completeAsJson("system", "user", SCHEMA));

		assertTrue(ex.getMessage().contains("거부"), ex.getMessage());
	}

	private void stubOk() {
		wireMock.stubFor(post(urlEqualTo(CHAT_PATH)).willReturn(okJson(chatResponse("{\"ok\":true}"))));
	}

	static String chatResponse(String content) {
		return """
				{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":%s}}]}
				""".formatted(MAPPER.writeValueAsString(content));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> lastRequestBody() {
		List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlEqualTo(CHAT_PATH)));
		return MAPPER.readValue(requests.get(requests.size() - 1).getBodyAsString(), Map.class);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> section(Map<String, Object> body, String key) {
		return (Map<String, Object>) body.get(key);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> messages(Map<String, Object> body) {
		return (List<Map<String, Object>>) body.get("messages");
	}

	private static OpenAiClient client() {
		return new OpenAiClient(RestClient.builder(), ClientHttpRequestFactoryBuilder.detect(),
				HttpClientSettings.defaults(), properties(Duration.ofSeconds(5), 3));
	}

	private static OpenAiProperties properties(Duration timeout, int maxRetries) {
		return new OpenAiProperties(wireMock.baseUrl() + "/v1", "test-key", "gpt-5-mini",
				timeout, maxRetries, FAST_BACKOFF);
	}
}
