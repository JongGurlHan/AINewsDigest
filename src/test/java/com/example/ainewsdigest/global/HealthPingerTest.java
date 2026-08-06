package com.example.ainewsdigest.global;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 데드맨스위치 핑 (ADR-010). 실제 healthchecks.io를 때리지 않는다 — CI가 외부 상태에 종속된다.
 *
 * <p>검증 대상은 "핑이 나가는가"보다 <b>"핑이 실패해도 발송이 멀쩡한가"</b>다. 감시 장치가 본 기능을
 * 망가뜨리면 감시를 붙이지 않느니만 못하다.
 */
class HealthPingerTest {

	private static final String PATH = "/ping/2fd4e1c6-7f3a-4b5e-8c9d-0a1b2c3d4e5f";

	@RegisterExtension
	static final WireMockExtension wireMock = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort().http2PlainDisabled(true))
			.build();

	@Test
	void sendsGetToTheConfiguredUrl() {
		wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(ok()));

		pinger(wireMock.baseUrl() + PATH).pingSuccess();

		wireMock.verify(1, getRequestedFor(urlEqualTo(PATH)));
	}

	/** healthchecks.io가 죽은 날 아침 발송까지 실패해서는 안 된다. */
	@Test
	void serverErrorDoesNotThrow() {
		wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(serverError()));

		assertDoesNotThrow(() -> pinger(wireMock.baseUrl() + PATH).pingSuccess());
	}

	/** 연결 자체가 안 되는 경우(방화벽·DNS). I/O 예외도 밖으로 나가지 않는다. */
	@Test
	void connectionFailureDoesNotThrow() {
		assertDoesNotThrow(() -> pinger("http://localhost:1/ping").pingSuccess());
	}

	/** URL이 비어 있는 것은 정상 상태다 — 감시를 쓰지 않는 배포. 호출조차 하지 않는다. */
	@Test
	void blankUrlSendsNothing() {
		wireMock.resetRequests();

		assertDoesNotThrow(() -> pinger("").pingSuccess());

		wireMock.verify(0, getRequestedFor(urlEqualTo(PATH)));
	}

	/** 설정 오타로 URL이 깨져도 마찬가지다. {@code URI.create}가 던지는 예외까지 여기서 막는다. */
	@Test
	void malformedUrlDoesNotThrow() {
		assertDoesNotThrow(() -> pinger("h ttp://nope").pingSuccess());
	}

	private static HealthPinger pinger(String url) {
		return new HealthPinger(RestClient.builder(), ClientHttpRequestFactoryBuilder.detect(),
				HttpClientSettings.defaults(), new OpsProperties(url));
	}
}
