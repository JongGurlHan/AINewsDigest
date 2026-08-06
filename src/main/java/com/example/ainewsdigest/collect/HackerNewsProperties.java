package com.example.ainewsdigest.collect;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * HN Algolia 수집 설정. 소스 URL을 코드에 하드코딩하지 않는다 — 설정으로 빼야 소스 추가·교체가
 * 재빌드 없이 가능하다.
 *
 * <p>{@code @Component}나 {@code @EnableConfigurationProperties}를 붙이지 않는다.
 * {@code AinewsdigestApplication}의 {@code @ConfigurationPropertiesScan}이 등록한다.
 *
 * <p>설정이 비어 있어도 기동은 되어야 하므로 값마다 기본값을 둔다. 운영 설정은
 * {@code application.yml}의 {@code ainewsdigest.collect.hacker-news}에 있다.
 *
 * @param baseUrl     Algolia API 베이스 URL (인증 키가 필요 없다)
 * @param minPoints   후보로 볼 최소 points
 * @param hitsPerPage 키워드당 요청 건수
 * @param keywords    검색 키워드. 키워드마다 한 번씩 호출하고 결과를 합친다
 */
@ConfigurationProperties("ainewsdigest.collect.hacker-news")
public record HackerNewsProperties(String baseUrl, Integer minPoints, Integer hitsPerPage, List<String> keywords) {

	private static final String DEFAULT_BASE_URL = "https://hn.algolia.com/api/v1";
	private static final int DEFAULT_MIN_POINTS = 30;
	private static final int DEFAULT_HITS_PER_PAGE = 40;

	public HackerNewsProperties {
		baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
		minPoints = (minPoints == null) ? DEFAULT_MIN_POINTS : minPoints;
		hitsPerPage = (hitsPerPage == null) ? DEFAULT_HITS_PER_PAGE : hitsPerPage;
		keywords = (keywords == null) ? List.of() : List.copyOf(keywords);
	}
}
