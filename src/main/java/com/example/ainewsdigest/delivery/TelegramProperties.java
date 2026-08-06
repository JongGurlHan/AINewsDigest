package com.example.ainewsdigest.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 텔레그램 Bot API 설정.
 *
 * <p>{@code @Component}나 {@code @EnableConfigurationProperties}를 붙이지 않는다.
 * {@code AinewsdigestApplication}의 {@code @ConfigurationPropertiesScan}이 등록한다.
 *
 * <p><b>{@code botToken}은 환경변수 {@code TELEGRAM_BOT_TOKEN}으로만 주입한다.</b> 토큰이 유출되면
 * 봇을 통째로 탈취당해 구독자 전원에게 임의의 메시지를 보낼 수 있다. 비어 있어도 기동은 되고 테스트는
 * 전부 WireMock을 쓰므로 통과한다 — 실제로 토큰이 없으면 호출 시점에 404가 오고 {@link SendResult}로 표현된다.
 *
 * @param pollTimeout 롱폴링 대기 시간의 <b>상한</b>. 읽기 타임아웃은 이 값 + 10초로 잡는다 — 읽기 쪽이
 *                    더 짧으면 매 사이클 예외가 나고 구독 기능이 통째로 죽는다 (ADR-015)
 * @param botUsername 구독 딥링크({@code https://t.me/<bot>?start=web})에 박히는 봇 이름 (ADR-003).
 *                    토큰과 달리 공개값이라 기본값을 둔다 — 비어 있으면 랜딩의 구독 버튼이 죽은 링크가 되고,
 *                    <b>구독 경로가 통째로 막혀도 화면은 정상으로 보인다</b>
 */
@ConfigurationProperties("ainewsdigest.telegram")
public record TelegramProperties(String baseUrl, String botToken, String adminChatId, Duration pollTimeout,
		String botUsername) {

	private static final String DEFAULT_BASE_URL = "https://api.telegram.org";

	private static final Duration DEFAULT_POLL_TIMEOUT = Duration.ofSeconds(30);

	private static final String DEFAULT_BOT_USERNAME = "ainewsdigest_bot";

	public TelegramProperties {
		baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
		botToken = (botToken == null) ? "" : botToken;
		adminChatId = (adminChatId == null) ? "" : adminChatId;
		pollTimeout = (pollTimeout == null || pollTimeout.isNegative()) ? DEFAULT_POLL_TIMEOUT : pollTimeout;
		botUsername = (botUsername == null || botUsername.isBlank()) ? DEFAULT_BOT_USERNAME : botUsername;
	}
}
