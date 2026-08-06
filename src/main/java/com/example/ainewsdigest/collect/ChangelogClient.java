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
 *
 * <h2>본문을 후보에 실어 보낸다</h2>
 * 다른 소스와 달리 <b>변경 목록(다음 헤딩 전까지)을 후보에 담는다.</b> 후보 URL이 raw
 * {@code .md}({@code text/plain})라 {@code JsoupArticleExtractor}가 "HTML이 아니다"로 반드시 거절하기
 * 때문이다. 실어 보내지 않으면 이 소스는 LLM 채점 비용만 쓰고 크롤링에서 100% 탈락한다 —
 * 하루도 빠짐없이, 영원히. 그리고 이미 파일 전문을 받아 섹션까지 파싱한 마당에 같은 내용을
 * 다시 받아오게 할 이유도 없다.
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

	/**
	 * 섹션의 끝. 버전 헤딩이 아니라 <b>모든 {@code ##} 헤딩</b>을 경계로 쓴다 —
	 * {@code ## Unreleased}가 뒤따르는 경우 그 내용까지 릴리즈 노트로 딸려가면 안 된다.
	 */
	private static final Pattern ANY_HEADING = Pattern.compile("^##\\s", Pattern.MULTILINE);

	/**
	 * 크롤링 경로의 {@code ainewsdigest.collect.extractor.max-content-length}와 같은 예산이다.
	 * 본문 출처가 다를 뿐 요약 프롬프트에 실리는 양은 같아야 한다.
	 */
	private static final int MAX_CONTENT_LENGTH = 3000;

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
		Optional<Release> release = latestRelease(markdown);
		if (release.isEmpty()) {
			// 형식이 바뀌었다는 뜻이다. 조용히 빈 결과로 넘어가면 이 소스가 죽은 채로 방치된다.
			log.warn("CHANGELOG에서 버전 헤딩을 찾지 못했다 (url={})", properties.url());
			return FetchResult.failure();
		}
		String version = release.get().version();
		return CandidateArticle
				.of(title(version), releaseUrl(version), SOURCE_NAME, 0, Instant.now(), normalizer,
						release.get().body())
				.map(article -> FetchResult.of(List.of(article)))
				.orElseGet(() -> FetchResult.of(List.of()));
	}

	private static Optional<Release> latestRelease(String markdown) {
		Matcher matcher = VERSION_HEADING.matcher(markdown);
		if (!matcher.find()) {
			return Optional.empty();
		}
		return Optional.of(new Release(matcher.group(1), body(markdown, matcher.end())));
	}

	/**
	 * 버전 헤딩 다음부터 그 다음 {@code ##} 헤딩 전까지. 변경 목록이 비어 있으면 {@code null}을 돌려
	 * 본문 없는 후보로 만든다 — 빈 본문을 실어 보내면 모델이 릴리즈 내용을 지어낸다.
	 */
	private static String body(String markdown, int fromIndex) {
		Matcher next = ANY_HEADING.matcher(markdown);
		int end = next.find(fromIndex) ? next.start() : markdown.length();
		String section = markdown.substring(fromIndex, end).strip();
		if (section.isEmpty()) {
			return null;
		}
		return truncate(section);
	}

	/**
	 * 코드포인트 경계에서 자른다. {@code substring}으로 char 수를 세면 이모지의 UTF-16 서로게이트
	 * 페어가 반으로 쪼개진다 — 릴리즈 노트에는 이모지가 흔하다.
	 */
	private static String truncate(String section) {
		int codePoints = section.codePointCount(0, section.length());
		if (codePoints <= MAX_CONTENT_LENGTH) {
			return section;
		}
		return section.substring(0, section.offsetByCodePoints(0, MAX_CONTENT_LENGTH));
	}

	/** 최신 릴리즈 한 건. 버전과 본문을 따로 찾으면 마크다운을 두 번 훑게 된다. */
	private record Release(String version, String body) {
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
