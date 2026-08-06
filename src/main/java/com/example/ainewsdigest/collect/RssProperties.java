package com.example.ainewsdigest.collect;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * RSS·Atom 피드 수집 설정. 피드 URL을 코드에 하드코딩하지 않는다.
 *
 * <p>{@code @Component}나 {@code @EnableConfigurationProperties}를 붙이지 않는다.
 * {@code AinewsdigestApplication}의 {@code @ConfigurationPropertiesScan}이 등록한다.
 */
@ConfigurationProperties("ainewsdigest.collect.rss")
public record RssProperties(List<Feed> feeds) {

	public RssProperties {
		feeds = (feeds == null) ? List.of() : List.copyOf(feeds);
	}

	/**
	 * @param name 후보의 {@code sourceName}으로 쓴다 (예: {@code OpenAI})
	 * @param url  RSS 2.0 또는 Atom 피드 URL
	 */
	public record Feed(String name, String url) {
	}
}
