package com.example.ainewsdigest.collect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub raw의 마크다운 CHANGELOG 수집기. <b>가장 최신 버전 섹션 하나만</b> 후보로 만든다.
 *
 * <p>Anthropic은 RSS를 제공하지 않아 Claude Code 릴리즈는 이 파일이 1차 출처다 (ADR-005).
 * 본문(다음 헤딩 전까지의 변경 목록)은 후보에 싣지 않는다 — {@code CandidateArticle}은 메타데이터만
 * 나르고, 본문은 step 3의 크롤링이 URL에서 얻는다.
 */
@Component
public class ChangelogClient implements NewsSource {

	private static final Logger log = LoggerFactory.getLogger(ChangelogClient.class);

	private static final String SOURCE_NAME = "Claude Code";

	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * {@code ## 2.1.222} 형태의 첫 번째 버전 헤딩. 숫자로 시작하는 토큰만 버전으로 인정한다 —
	 * {@code ## Unreleased} 같은 헤딩을 버전으로 오인하면 매일 같은 후보가 나간다.
	 */
	private static final Pattern VERSION_HEADING =
			Pattern.compile("^##\\s+v?(\\d[\\w.\\-]*)", Pattern.MULTILINE);

	private final RestClient restClient;

	private final ChangelogProperties properties;

	private final UrlNormalizer normalizer;

	public ChangelogClient(RestClient.Builder builder,
			ClientHttpRequestFactoryBuilder<?> factoryBuilder,
			HttpClientSettings defaults,
			ChangelogProperties properties,
			UrlNormalizer normalizer) {
		this.restClient = builder
				.requestFactory(factoryBuilder.build(defaults.withReadTimeout(READ_TIMEOUT)))
				.build();
		this.properties = properties;
		this.normalizer = normalizer;
	}

	@Override
	public String name() {
		return SOURCE_NAME + " CHANGELOG";
	}

	/**
	 * 파일에 게시 날짜가 없으므로 {@code since} 필터를 적용하지 않고 {@code publishedAt}은
	 * {@code Instant.now()}를 쓴다. 같은 버전이 다음 날 또 후보가 되는 것은 step 6의
	 * {@code normalized_url} 중복 제거가 막는다.
	 */
	@Override
	public FetchResult fetch(Instant since) {
		String markdown;
		try {
			markdown = restClient.get().uri(properties.url()).retrieve().body(String.class);
		}
		catch (RuntimeException ex) {
			log.warn("CHANGELOG 수집 실패 (url={}): {}", properties.url(), ex.toString());
			return FetchResult.failure();
		}
		if (markdown == null) {
			return FetchResult.failure();
		}
		Optional<String> version = latestVersion(markdown);
		if (version.isEmpty()) {
			// 형식이 바뀌었다는 뜻이다. 조용히 빈 결과로 넘어가면 이 소스가 죽은 채로 방치된다.
			log.warn("CHANGELOG에서 버전 헤딩을 찾지 못했다 (url={})", properties.url());
			return FetchResult.failure();
		}
		return CandidateArticle
				.of(title(version.get()), releaseUrl(version.get()), SOURCE_NAME, 0, Instant.now(), normalizer)
				.map(article -> FetchResult.of(List.of(article)))
				.orElseGet(() -> FetchResult.of(List.of()));
	}

	private static Optional<String> latestVersion(String markdown) {
		Matcher matcher = VERSION_HEADING.matcher(markdown);
		return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
	}

	private static String title(String version) {
		return SOURCE_NAME + " " + version + " 릴리즈";
	}

	/**
	 * 버전을 <b>쿼리 파라미터</b>로 붙인다. {@code UrlNormalizer}가 fragment를 제거하므로
	 * 앵커({@code #2.1.222})를 쓰면 모든 버전이 같은 URL로 정규화되고, 최초 1회 발송 후
	 * 영원히 중복으로 걸러진다. 쿼리 파라미터는 추적 파라미터 목록에 없어 정규화 후에도 살아남는다.
	 */
	private String releaseUrl(String version) {
		String url = properties.url();
		return url + (url.contains("?") ? "&" : "?") + "v=" + version;
	}
}
