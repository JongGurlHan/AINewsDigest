package com.example.ainewsdigest.digest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 텔레그램 메시지 조립 설정.
 *
 * <p>{@code @Component}나 {@code @EnableConfigurationProperties}를 붙이지 않는다.
 * {@code AinewsdigestApplication}의 {@code @ConfigurationPropertiesScan}이 등록한다.
 *
 * @param maxVisibleLength 메시지의 보이는 길이 상한. 텔레그램 한도는 4,096자지만 여유를 두고 4,000자로 잡는다.
 *                         한도를 꽉 채우면 이모지 하나, 줄바꿈 하나가 늘어난 날 400이 난다
 * @param emptyText        채택된 기사가 0건인 날의 본문. PRD가 정한 문구이며 침묵하지 않는다는 약속이다 (ADR-013)
 */
@ConfigurationProperties("ainewsdigest.message")
public record MessageProperties(Integer maxVisibleLength, String emptyText) {

	private static final int DEFAULT_MAX_VISIBLE_LENGTH = 4000;
	private static final String DEFAULT_EMPTY_TEXT = "오늘의 AI 뉴스는 없습니다.";

	public MessageProperties {
		maxVisibleLength = (maxVisibleLength == null) ? DEFAULT_MAX_VISIBLE_LENGTH : maxVisibleLength;
		emptyText = (emptyText == null || emptyText.isBlank()) ? DEFAULT_EMPTY_TEXT : emptyText;
	}
}
