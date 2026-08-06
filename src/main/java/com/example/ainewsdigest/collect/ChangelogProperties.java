package com.example.ainewsdigest.collect;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 마크다운 CHANGELOG 수집 설정.
 *
 * <p>{@code @Component}나 {@code @EnableConfigurationProperties}를 붙이지 않는다.
 * {@code AinewsdigestApplication}의 {@code @ConfigurationPropertiesScan}이 등록한다.
 *
 * @param url GitHub raw 마크다운 URL
 */
@ConfigurationProperties("ainewsdigest.collect.changelog")
public record ChangelogProperties(String url) {

	private static final String DEFAULT_URL =
			"https://raw.githubusercontent.com/anthropics/claude-code/main/CHANGELOG.md";

	public ChangelogProperties {
		url = (url == null || url.isBlank()) ? DEFAULT_URL : url;
	}
}
