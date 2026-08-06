package com.example.ainewsdigest.delivery;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 텔레그램 Bot API 어댑터. 발송({@link Messenger})과 업데이트 수신({@link UpdateSource})이 같은
 * 호스트·같은 인증(경로에 박힌 봇 토큰)을 쓰므로 한 클래스에 둔다.
 *
 * <p>업데이트 수신은 웹훅이 아니라 롱폴링이다 (ADR-008). 여기서는 {@code getUpdates}를 <b>한 번</b>
 * 호출하는 것까지만 한다 — 폴링 루프는 step 8의 몫이다.
 *
 * <p><b>어떤 경우에도 예외를 밖으로 던지지 않는다 (ADR-016).</b> 발송 루프에서 예외가 튀면 한 명의
 * 실패가 나머지 구독자를 통째로 날리고, 폴링에서 튀면 백그라운드 스레드가 죽어 구독 기능이 멈춘다.
 *
 * <p><b>로그·반환 문자열에 봇 토큰을 남기지 않는다.</b> 텔레그램은 토큰을 URL 경로에 넣으므로
 * ({@code /bot<token>/sendMessage}) URL을 직접 찍지 않아도 새어 나간다 — Spring의
 * {@code ResourceAccessException} 메시지에는 요청 URL이 통째로 들어 있다. 롱폴링은 타임아웃이
 * 일상이라 {@code log.warn("...", e)} 한 줄이면 토큰이 매 사이클 로그에 쌓인다. 토큰이 유출되면
 * 봇을 탈취당해 구독자 전원에게 임의의 메시지가 나간다. {@link #mask(String)}을 거치지 않은 문자열은
 * 로그에도 {@link SendResult}·{@link PollResult}에도 넣지 않는다.
 */
@Component
public class TelegramClient implements Messenger, UpdateSource {

	private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

	/** 발송용 읽기 타임아웃 (ADR-015). 전역 기본값이 아니라 이 값을 쓴다. */
	private static final Duration SEND_READ_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * 폴링용 읽기 타임아웃의 여유분. 읽기 타임아웃이 폴링 타임아웃보다 짧으면 매 사이클 예외가 나고
	 * 구독 기능이 통째로 죽는다 (ADR-015).
	 */
	private static final Duration POLL_READ_MARGIN = Duration.ofSeconds(10);

	/** 이 서비스가 다루는 것은 텍스트 명령뿐이다. 채널 포스트·인라인 쿼리 등은 받지 않는다. */
	private static final String ALLOWED_UPDATES = "[\"message\"]";

	/**
	 * {@code /bot<숫자>:<영숫자>} 형태의 토큰. URL 인코딩으로 콜론이 {@code %3A}가 된 형태도 잡는다 —
	 * 인코딩된 쪽을 놓치면 마스킹을 통과한 척하면서 토큰이 그대로 나간다.
	 */
	private static final Pattern BOT_TOKEN_IN_URL = Pattern.compile("/bot\\d+(?::|%3[Aa])[A-Za-z0-9_%-]+");

	private static final String MASKED_TOKEN = "/bot***";

	/** 관리자 알림으로 나가는 문자열이다. 서버가 HTML 오류 페이지를 뱉어도 통째로 싣지 않는다. */
	private static final int MAX_DESCRIPTION_LENGTH = 200;

	/** 응답에 새 필드가 추가돼도 파싱이 깨지지 않아야 한다. 우리가 쓰는 필드만 선언한다. */
	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();

	private final RestClient sendClient;

	private final RestClient pollingClient;

	private final TelegramProperties properties;

	/**
	 * <b>{@code RestClient}를 두 개 만든다.</b> 발송은 10초 안에 포기해야 하고 폴링은 30초 넘게 기다려야
	 * 정상이라 하나로 합칠 수 없다 (ADR-015). 전역 {@code spring.http.client.read-timeout}은 수집용
	 * 10초라서 그대로 쓰면 롱폴링이 매 사이클 터진다.
	 */
	public TelegramClient(RestClient.Builder builder,
			ClientHttpRequestFactoryBuilder<?> factoryBuilder,
			HttpClientSettings defaults,
			TelegramProperties properties) {
		this.sendClient = builder.clone()
				.baseUrl(properties.baseUrl())
				.requestFactory(factoryBuilder.build(defaults.withReadTimeout(SEND_READ_TIMEOUT)))
				.build();
		this.pollingClient = builder.clone()
				.baseUrl(properties.baseUrl())
				.requestFactory(factoryBuilder.build(
						defaults.withReadTimeout(properties.pollTimeout().plus(POLL_READ_MARGIN))))
				.build();
		this.properties = properties;
	}

	@Override
	public SendResult send(long chatId, String html) {
		ResponseEntity<String> response;
		try {
			response = sendClient.post()
					.uri(path("sendMessage"))
					.contentType(MediaType.APPLICATION_JSON)
					.body(sendBody(chatId, html))
					.retrieve()
					// 상태 코드로 분기하므로 예외로 만들지 않는다. 기본 핸들러는 4xx·5xx를 던진다.
					.onStatus(status -> true, (request, ignored) -> {
					})
					.toEntity(String.class);
		}
		catch (RuntimeException ex) {
			// 타임아웃·연결 실패. 잠시 뒤 살아날 수 있으므로 재시도 가능으로 표시한다.
			String reason = mask(ex.toString());
			log.warn("텔레그램 발송 실패 (chatId={}): {}", chatId, reason);
			return new SendResult.Failed("IO_ERROR", reason, true);
		}
		return interpret(chatId, response);
	}

	@Override
	public PollResult getUpdates(long offset, int timeoutSeconds) {
		int pollSeconds = boundedPollSeconds(timeoutSeconds);
		ResponseEntity<String> response;
		try {
			response = pollingClient.get()
					.uri(uriBuilder -> uriBuilder.path(path("getUpdates"))
							.queryParam("offset", offset)
							.queryParam("timeout", pollSeconds)
							.queryParam("allowed_updates", ALLOWED_UPDATES)
							.build())
					.retrieve()
					.onStatus(status -> true, (request, ignored) -> {
					})
					.toEntity(String.class);
		}
		catch (RuntimeException ex) {
			return pollFailure(mask(ex.toString()));
		}
		UpdatesResponse body = parse(response.getBody(), UpdatesResponse.class);
		if (!response.getStatusCode().is2xxSuccessful() || body == null || !body.ok()) {
			return pollFailure("HTTP %d %s".formatted(response.getStatusCode().value(),
					describe(body == null ? null : body.description(), response.getBody())));
		}
		return new PollResult.Updates(toUpdates(body.result()));
	}

	/**
	 * 요청하는 대기 시간을 설정된 {@code poll-timeout} 이하로 제한한다. 읽기 타임아웃은 그 값을 기준으로
	 * 고정돼 있어서, 더 긴 대기를 요청하면 응답이 오기 전에 읽기 쪽이 먼저 터진다.
	 */
	private int boundedPollSeconds(int timeoutSeconds) {
		long configured = properties.pollTimeout().toSeconds();
		return (int) Math.max(0, Math.min(timeoutSeconds, configured));
	}

	private PollResult pollFailure(String reason) {
		log.warn("텔레그램 폴링 실패: {}", reason);
		return new PollResult.Failure(reason);
	}

	/** 토큰을 URI 변수로 넘기지 않는다. 엄격 인코딩이 콜론을 {@code %3A}로 바꿔 마스킹 대상이 어긋난다. */
	private String path(String method) {
		return "/bot" + properties.botToken() + "/" + method;
	}

	private static Map<String, Object> sendBody(long chatId, String html) {
		return Map.of(
				"chat_id", chatId,
				"text", html,
				// MarkdownV2는 이스케이프 대상이 15자라 한글 요약의 마침표 하나로 발송 전체가 400이 된다 (ADR-009).
				"parse_mode", "HTML",
				// 끄지 않으면 첫 URL의 미리보기 카드가 붙어 메시지가 지저분해진다.
				"link_preview_options", Map.of("is_disabled", true));
	}

	private SendResult interpret(long chatId, ResponseEntity<String> response) {
		int status = response.getStatusCode().value();
		SendResponse body = parse(response.getBody(), SendResponse.class);
		if (response.getStatusCode().is2xxSuccessful() && body != null && body.ok()
				&& body.result() != null && body.result().messageId() != null) {
			return new SendResult.Success(body.result().messageId());
		}
		String description = describe(body == null ? null : body.description(), response.getBody());
		if (status == HttpStatus.FORBIDDEN.value()) {
			// 정상 흐름이다. 봇을 차단했거나 대화를 지운 구독자는 step 9가 UNSUBSCRIBED로 전환한다.
			log.info("텔레그램 발송 차단됨 (chatId={}): {}", chatId, description);
			return new SendResult.Blocked(description);
		}
		if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
			int retryAfter = (body != null && body.parameters() != null && body.parameters().retryAfter() != null)
					? body.parameters().retryAfter() : 1;
			log.warn("텔레그램 발송 제한됨 (chatId={}, retryAfter={}s)", chatId, retryAfter);
			return new SendResult.RateLimited(Math.max(retryAfter, 0));
		}
		// 4xx는 요청 자체가 잘못된 것이라 다시 보내도 같은 답이 온다. 5xx만 재시도한다.
		boolean retryable = response.getStatusCode().is5xxServerError();
		log.warn("텔레그램 발송 실패 (chatId={}, status={}, retryable={}): {}",
				chatId, status, retryable, description);
		return new SendResult.Failed(String.valueOf(status), description, retryable);
	}

	private static List<TelegramUpdate> toUpdates(List<Update> raw) {
		if (raw == null) {
			return List.of();
		}
		List<TelegramUpdate> updates = new ArrayList<>();
		for (Update update : raw) {
			if (update == null || update.updateId() == null || update.message() == null) {
				continue;
			}
			Message message = update.message();
			// 사진·스티커 등 텍스트가 없는 메시지는 해석할 명령이 없다.
			if (message.text() == null || message.text().isBlank()
					|| message.chat() == null || message.chat().id() == null) {
				continue;
			}
			updates.add(new TelegramUpdate(update.updateId(), message.chat().id(), message.text()));
		}
		return updates;
	}

	/**
	 * 텔레그램이 준 {@code description}을 쓰고, 없으면 응답 본문을 쓴다 (프록시가 낀 경우 JSON이 아닐 수 있다).
	 * 둘 다 마스킹·절단을 거친다.
	 */
	private String describe(String description, String rawBody) {
		String text = (description == null || description.isBlank()) ? rawBody : description;
		return mask(text);
	}

	/**
	 * <b>모든 로깅·반환 문자열이 이 메서드를 통과해야 한다.</b> URL에 박힌 토큰을 표식으로 바꾸고, 형식이
	 * 달라 정규식에 걸리지 않는 경우에 대비해 설정된 토큰 자체도 한 번 더 지운다.
	 */
	private String mask(String text) {
		if (text == null) {
			return "";
		}
		String masked = BOT_TOKEN_IN_URL.matcher(text).replaceAll(MASKED_TOKEN);
		String token = properties.botToken();
		if (!token.isBlank()) {
			masked = masked.replace(token, "***");
		}
		return truncate(masked);
	}

	private static String truncate(String text) {
		if (text.codePointCount(0, text.length()) <= MAX_DESCRIPTION_LENGTH) {
			return text;
		}
		return text.substring(0, text.offsetByCodePoints(0, MAX_DESCRIPTION_LENGTH));
	}

	/**
	 * 본문이 JSON이 아니어도 예외를 내지 않는다. 해석하지 못한 본문은 상태 코드만으로 분기하면 되고,
	 * 여기서 예외가 튀면 발송 루프가 멈춘다.
	 */
	private static <T> T parse(String body, Class<T> type) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			return MAPPER.readValue(body, type);
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	record SendResponse(boolean ok, MessageResult result, String description, ResponseParameters parameters) {
	}

	record MessageResult(@JsonProperty("message_id") Long messageId) {
	}

	record ResponseParameters(@JsonProperty("retry_after") Integer retryAfter) {
	}

	record UpdatesResponse(boolean ok, List<Update> result, String description) {
	}

	record Update(@JsonProperty("update_id") Long updateId, Message message) {
	}

	record Message(Chat chat, String text) {
	}

	record Chat(Long id) {
	}
}
