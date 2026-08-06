package com.example.ainewsdigest.delivery;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 {@code api.telegram.org}를 호출하지 않는다. 봇 토큰이 필요하고 CI가 외부 상태에 종속된다.
 */
class TelegramClientTest {

	/** 로그·에러 문자열에서 눈으로 찾기 쉬운 더미 토큰. 실제 토큰과 형식만 같다. */
	private static final String TOKEN = "8123456:AAHdummyTokenValue";

	/** 토큰의 비밀 부분. URL 인코딩(`%3A`)으로 콜론이 바뀌어도 이 문자열은 그대로 남는다. */
	private static final String TOKEN_SECRET = "AAHdummyTokenValue";

	private static final String SEND_PATH = "/bot" + TOKEN + "/sendMessage";

	private static final String UPDATES_PATH = "/bot" + TOKEN + "/getUpdates";

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	/**
	 * {@code http2PlainDisabled}: 평문 HTTP에서 JDK HttpClient가 h2c 업그레이드를 시도하면 POST 본문이
	 * 유실된다. 운영에서 텔레그램은 TLS + ALPN으로 붙으므로 이 경로를 타지 않는다.
	 */
	@RegisterExtension
	static final WireMockExtension wireMock = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort().http2PlainDisabled(true))
			.build();

	@Test
	void returnsSuccessWithMessageId() {
		wireMock.stubFor(post(urlEqualTo(SEND_PATH))
				.willReturn(okJson("{\"ok\":true,\"result\":{\"message_id\":4242}}")));

		SendResult result = client().send(1000L, "<b>안녕</b>");

		assertEquals(4242L, assertInstanceOf(SendResult.Success.class, result).messageId());
	}

	/**
	 * MarkdownV2는 이스케이프 대상이 15자라 한글 요약의 마침표 하나로 매일 400이 난다. HTML은 셋뿐이다 (ADR-009).
	 * 링크 프리뷰를 끄지 않으면 첫 URL의 미리보기 카드가 붙어 메시지가 지저분해진다.
	 */
	@Test
	void sendsHtmlParseModeAndDisablesLinkPreview() {
		wireMock.stubFor(post(urlEqualTo(SEND_PATH))
				.willReturn(okJson("{\"ok\":true,\"result\":{\"message_id\":1}}")));

		client().send(777L, "<b>제목</b>\n요약");

		Map<String, Object> body = lastSendBody();
		assertEquals("HTML", body.get("parse_mode"));
		assertEquals(Map.of("is_disabled", true), body.get("link_preview_options"));
		assertEquals(777L, ((Number) body.get("chat_id")).longValue());
		assertEquals("<b>제목</b>\n요약", body.get("text"));
	}

	/** 403은 차단이다. 재시도 대상이 아니라 구독 해지 대상이라 다른 분기로 돌려준다. */
	@Test
	void returnsBlockedOnForbidden() {
		wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(403)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"ok\":false,\"error_code\":403,"
						+ "\"description\":\"Forbidden: bot was blocked by the user\"}")));

		SendResult result = client().send(1000L, "<b>안녕</b>");

		SendResult.Blocked blocked = assertInstanceOf(SendResult.Blocked.class, result);
		assertTrue(blocked.description().contains("blocked by the user"), blocked.description());
	}

	@Test
	void returnsRateLimitedWithRetryAfter() {
		wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(429)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 7\","
						+ "\"parameters\":{\"retry_after\":7}}")));

		SendResult result = client().send(1000L, "<b>안녕</b>");

		assertEquals(7, assertInstanceOf(SendResult.RateLimited.class, result).retryAfterSeconds());
	}

	/** 400은 요청 자체가 잘못된 것이다. 다시 보내도 같은 결과라 재시도 대상이 아니다. */
	@Test
	void badRequestIsNotRetryable() {
		wireMock.stubFor(post(urlEqualTo(SEND_PATH))
				.willReturn(badRequest().withHeader("Content-Type", "application/json")
						.withBody("{\"ok\":false,\"error_code\":400,"
								+ "\"description\":\"Bad Request: can't parse entities\"}")));

		SendResult.Failed failed = assertInstanceOf(SendResult.Failed.class, client().send(1000L, "<b"));

		assertFalse(failed.retryable());
		assertEquals("400", failed.errorCode());
		assertTrue(failed.description().contains("parse entities"), failed.description());
	}

	@Test
	void serverErrorIsRetryable() {
		wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(serverError()));

		SendResult.Failed failed = assertInstanceOf(SendResult.Failed.class, client().send(1000L, "<b>안녕</b>"));

		assertTrue(failed.retryable());
		assertEquals("500", failed.errorCode());
	}

	/**
	 * 연결 실패·읽기 타임아웃은 같은 {@code ResourceAccessException} 경로다. 여기서는 죽은 포트로
	 * 연결 실패를 만든다 — 읽기 타임아웃(10초)을 실제로 기다리지 않고 같은 분기를 검증하기 위해서다.
	 * <b>예외가 밖으로 나오면 발송 루프가 여기서 멈춰 나머지 구독자가 통째로 날아간다.</b>
	 */
	@Test
	void connectionFailureIsRetryableAndDoesNotThrow() {
		SendResult result = deadPortClient().send(1000L, "<b>안녕</b>");

		SendResult.Failed failed = assertInstanceOf(SendResult.Failed.class, result);
		assertTrue(failed.retryable());
	}

	@Test
	void returnsUpdatesWithChatIdAndText() {
		wireMock.stubFor(get(urlPathEqualTo(UPDATES_PATH)).willReturn(okJson("""
				{"ok":true,"result":[
				  {"update_id":10,"message":{"message_id":1,"chat":{"id":555,"type":"private"},"text":"/start web"}},
				  {"update_id":11,"message":{"message_id":2,"chat":{"id":666,"type":"private"},"text":"/stop"}}
				]}
				""")));

		PollResult result = client().getUpdates(10L, 30);

		List<TelegramUpdate> updates = assertInstanceOf(PollResult.Updates.class, result).updates();
		assertEquals(List.of(new TelegramUpdate(10L, 555L, "/start web"),
				new TelegramUpdate(11L, 666L, "/stop")), updates);
		wireMock.verify(getRequestedFor(urlPathEqualTo(UPDATES_PATH))
				.withQueryParam("offset", equalTo("10"))
				.withQueryParam("timeout", equalTo("30"))
				.withQueryParam("allowed_updates", equalTo("[\"message\"]")));
	}

	/** 사진·스티커는 해석할 명령이 없다. 그 건만 빠지고 나머지는 그대로 온다. */
	@Test
	void skipsUpdatesWithoutText() {
		wireMock.stubFor(get(urlPathEqualTo(UPDATES_PATH)).willReturn(okJson("""
				{"ok":true,"result":[
				  {"update_id":20,"message":{"message_id":1,"chat":{"id":555,"type":"private"},"photo":[]}},
				  {"update_id":21,"message":{"message_id":2,"chat":{"id":555,"type":"private"},"text":"/help"}},
				  {"update_id":22,"edited_message":{"message_id":3,"chat":{"id":555},"text":"수정됨"}}
				]}
				""")));

		PollResult result = client().getUpdates(20L, 30);

		assertEquals(List.of(new TelegramUpdate(21L, 555L, "/help")),
				assertInstanceOf(PollResult.Updates.class, result).updates());
	}

	/** 실패를 빈 리스트로 표현하면 폴러가 백오프를 걸 방법이 없어진다 (ADR-016). */
	@Test
	void serverErrorBecomesFailureNotEmptyUpdates() {
		wireMock.stubFor(get(urlPathEqualTo(UPDATES_PATH)).willReturn(serverError()));

		PollResult result = client().getUpdates(0L, 30);

		assertInstanceOf(PollResult.Failure.class, result);
	}

	/** 롱폴링의 가장 흔한 정상 응답이다. 위의 실패와 <b>반드시</b> 구분되어야 한다. */
	@Test
	void emptyResultIsUpdatesNotFailure() {
		wireMock.stubFor(get(urlPathEqualTo(UPDATES_PATH)).willReturn(okJson("{\"ok\":true,\"result\":[]}")));

		PollResult result = client().getUpdates(0L, 30);

		assertEquals(List.of(), assertInstanceOf(PollResult.Updates.class, result).updates());
	}

	/**
	 * 읽기 타임아웃이 폴링 타임아웃보다 짧으면 매 사이클 예외가 나고 구독 기능이 통째로 죽는다 (ADR-015).
	 * poll-timeout(1초)보다 오래 끄는 응답이 정상 수신되면 읽기 타임아웃이 그보다 크다는 뜻이다.
	 */
	@Test
	void pollingReadTimeoutIsLongerThanPollTimeout() {
		wireMock.stubFor(get(urlPathEqualTo(UPDATES_PATH))
				.willReturn(okJson("{\"ok\":true,\"result\":[]}").withFixedDelay(1_500)));

		PollResult result = client(Duration.ofSeconds(1)).getUpdates(0L, 1);

		assertInstanceOf(PollResult.Updates.class, result);
	}

	/**
	 * 텔레그램은 토큰을 <b>URL 경로</b>에 넣는다. Spring의 I/O 예외 메시지에는 요청 URL이 통째로 들어 있어
	 * {@code log.warn("...", e)} 한 줄이면 토큰이 로그에 평문으로 쌓인다. 롱폴링은 실패가 일상이라
	 * 매 사이클 반복된다.
	 */
	@Test
	void doesNotLogBotToken() {
		ListAppender<ILoggingEvent> appender = attachAppender();
		try {
			TelegramClient client = deadPortClient();
			client.send(1000L, "<b>안녕</b>");
			client.getUpdates(0L, 1);

			List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
			assertFalse(messages.isEmpty(), "실패가 로그에 전혀 남지 않으면 이 테스트는 아무것도 검증하지 못한다");
			for (String message : messages) {
				assertFalse(message.contains(TOKEN), message);
				assertFalse(message.contains(TOKEN_SECRET), message);
			}
			// 토큰이 든 URL이 실제로 로깅 경로를 지나갔는지 확인한다. 마스킹 표식이 없으면 검증이 헛돈 것이다.
			assertTrue(messages.stream().anyMatch(message -> message.contains("/bot***")), messages.toString());
			// 예외 객체를 그대로 넘기면 스택트레이스와 원본 메시지가 통째로 찍힌다.
			assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
		}
		finally {
			detachAppender(appender);
		}
	}

	/** 이 값들은 step 9·11을 거쳐 관리자 알림 메시지로 텔레그램에 발송된다. */
	@Test
	void failureReasonAndDescriptionDoNotLeakBotToken() {
		TelegramClient client = deadPortClient();

		SendResult.Failed failed = assertInstanceOf(SendResult.Failed.class, client.send(1000L, "<b>안녕</b>"));
		PollResult.Failure failure = assertInstanceOf(PollResult.Failure.class, client.getUpdates(0L, 1));

		assertFalse(failed.description().contains(TOKEN_SECRET), failed.description());
		assertFalse(failure.reason().contains(TOKEN_SECRET), failure.reason());
		assertTrue(failed.description().contains("/bot***"), failed.description());
		assertTrue(failure.reason().contains("/bot***"), failure.reason());
	}

	@Test
	void defaultsKeepBotTokenEmptyAndPollTimeoutAtThirtySeconds() {
		TelegramProperties defaults = new TelegramProperties(null, null, null, null, null);

		assertEquals("https://api.telegram.org", defaults.baseUrl());
		// 토큰을 코드에 두지 않는다. 비어 있어도 기동은 되어야 한다.
		assertEquals("", defaults.botToken());
		assertEquals(Duration.ofSeconds(30), defaults.pollTimeout());
		// 토큰과 달리 공개값이다. 비면 랜딩의 구독 버튼이 죽은 링크가 되므로 기본값을 둔다.
		assertEquals("ainewsdigest_bot", defaults.botUsername());
	}

	private static ListAppender<ILoggingEvent> attachAppender() {
		ch.qos.logback.classic.Logger logger =
				(ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TelegramClient.class);
		logger.setLevel(Level.DEBUG);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private static void detachAppender(ListAppender<ILoggingEvent> appender) {
		ch.qos.logback.classic.Logger logger =
				(ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TelegramClient.class);
		logger.detachAppender(appender);
		logger.setLevel(null);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> lastSendBody() {
		List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlEqualTo(SEND_PATH)));
		return MAPPER.readValue(requests.get(requests.size() - 1).getBodyAsString(), Map.class);
	}

	private static TelegramClient client() {
		return client(Duration.ofSeconds(30));
	}

	private static TelegramClient client(Duration pollTimeout) {
		return client(wireMock::baseUrl, pollTimeout);
	}

	/** 아무도 듣지 않는 포트를 향한다. 연결이 즉시 거부되어 I/O 실패 경로를 결정적으로 태운다. */
	private static TelegramClient deadPortClient() {
		return client(() -> "http://localhost:" + freePort(), Duration.ofSeconds(1));
	}

	private static TelegramClient client(Supplier<String> baseUrl, Duration pollTimeout) {
		return new TelegramClient(RestClient.builder(), ClientHttpRequestFactoryBuilder.detect(),
				HttpClientSettings.defaults(),
				new TelegramProperties(baseUrl.get(), TOKEN, "9999", pollTimeout, null));
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
