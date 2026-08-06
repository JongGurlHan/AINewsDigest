package com.example.ainewsdigest.collect;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hacker News Algolia API 수집기. 인증 키가 필요 없다.
 *
 * <p>HN은 "개발자가 실제로 반응한 것"만 상위로 올라오므로 이 서비스의 목표와 정확히 맞는다.
 * Anthropic은 RSS를 제공하지 않는데 발표가 거의 항상 HN 상단에 올라오므로 HN이 그 구멍을 메운다 (ADR-005).
 */
@Component
public class HackerNewsClient implements NewsSource {

	private static final Logger log = LoggerFactory.getLogger(HackerNewsClient.class);

	private static final String SOURCE_NAME = "Hacker News";

	/** 느린 소스 하나가 배치를 잡아두면 안 된다. 실패하면 그 소스만 버린다. */
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	private final RestClient restClient;

	private final HackerNewsProperties properties;

	private final UrlNormalizer normalizer;

	/**
	 * 타임아웃은 전역 프로퍼티가 아니라 여기서 지정한다. 어댑터마다 필요한 시간이 6배까지 차이나기
	 * 때문이다 — 롱폴링은 30초 이상 기다려야 정상이고 LLM 요약은 60초가 필요한데 수집은 10초 안에
	 * 포기해야 한다 (ADR-015). {@code RestClient.Builder}·{@code ClientHttpRequestFactoryBuilder}·
	 * {@code HttpClientSettings}는 전부 Boot 자동 구성 빈이다.
	 */
	public HackerNewsClient(RestClient.Builder builder,
			ClientHttpRequestFactoryBuilder<?> factoryBuilder,
			HttpClientSettings defaults,
			HackerNewsProperties properties,
			UrlNormalizer normalizer) {
		this.restClient = builder
				.baseUrl(properties.baseUrl())
				.requestFactory(factoryBuilder.build(defaults.withReadTimeout(READ_TIMEOUT)))
				.build();
		this.properties = properties;
		this.normalizer = normalizer;
	}

	@Override
	public String name() {
		return SOURCE_NAME;
	}

	/**
	 * 키워드마다 한 번씩 호출하고 결과를 {@code normalizedUrl} 기준으로 합친다.
	 * 같은 기사가 여러 키워드에 걸리는 것이 정상이므로 여기서 한 번 접어둔다.
	 */
	@Override
	public FetchResult fetch(Instant since) {
		Map<String, CandidateArticle> byNormalizedUrl = new LinkedHashMap<>();
		boolean failed = false;
		for (String keyword : properties.keywords()) {
			try {
				for (Hit hit : hits(search(keyword, since))) {
					toCandidate(hit).ifPresent(article ->
							byNormalizedUrl.putIfAbsent(article.normalizedUrl(), article));
				}
			}
			catch (RuntimeException ex) {
				log.warn("Hacker News 수집 실패 (keyword={}): {}", keyword, ex.toString());
				failed = true;
			}
		}
		List<CandidateArticle> articles = List.copyOf(byNormalizedUrl.values());
		return failed ? FetchResult.partial(articles) : FetchResult.of(articles);
	}

	private SearchResponse search(String keyword, Instant since) {
		return restClient.get()
				.uri(uriBuilder -> uriBuilder.path("/search_by_date")
						.queryParam("tags", "story")
						.queryParam("numericFilters", "{numericFilters}")
						.queryParam("query", "{query}")
						.queryParam("hitsPerPage", "{hitsPerPage}")
						.build(numericFilters(since), keyword, properties.hitsPerPage()))
				.retrieve()
				.body(SearchResponse.class);
	}

	private String numericFilters(Instant since) {
		return "created_at_i>" + since.getEpochSecond() + ",points>" + properties.minPoints();
	}

	private static List<Hit> hits(SearchResponse response) {
		return (response == null || response.hits() == null) ? List.of() : response.hits();
	}

	private Optional<CandidateArticle> toCandidate(Hit hit) {
		// url이 null인 hit(Ask HN 등 자체 글)은 외부 원문이 없어 본문 크롤링(step 3)이 불가능하다.
		if (hit.url() == null) {
			return Optional.empty();
		}
		Instant publishedAt = parseInstant(hit.createdAt());
		if (publishedAt == null) {
			return Optional.empty();
		}
		// sourceDomain은 원문 URL에서 뽑는다. news.ycombinator.com이 아니다.
		return CandidateArticle.of(hit.title(), hit.url(), SOURCE_NAME,
				hit.points() == null ? 0 : hit.points(), publishedAt, normalizer);
	}

	private static Instant parseInstant(String createdAt) {
		if (createdAt == null) {
			return null;
		}
		try {
			return Instant.parse(createdAt);
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	record SearchResponse(List<Hit> hits) {
	}

	record Hit(String title, String url, Integer points, @JsonProperty("created_at") String createdAt) {
	}
}
