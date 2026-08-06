package com.example.ainewsdigest.collect;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * 공식 RSS 2.0 / Atom 피드 수집기. rome의 {@link SyndFeedInput}이 두 형식을 모두 파싱한다.
 *
 * <p>피드 하나가 죽어도 나머지는 계속 처리하되 {@link FetchResult#failed()}를 세운다 — 부분 실패도 실패다.
 */
@Component
public class RssFeedClient implements NewsSource {

	private static final Logger log = LoggerFactory.getLogger(RssFeedClient.class);

	private static final String SOURCE_NAME = "RSS";

	/** 느린 피드 하나가 배치를 잡아두면 안 된다 (ADR-015). */
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	private final RestClient restClient;

	private final RssProperties properties;

	private final UrlNormalizer normalizer;

	public RssFeedClient(RestClient.Builder builder,
			ClientHttpRequestFactoryBuilder<?> factoryBuilder,
			HttpClientSettings defaults,
			RssProperties properties,
			UrlNormalizer normalizer) {
		this.restClient = builder
				.requestFactory(factoryBuilder.build(defaults.withReadTimeout(READ_TIMEOUT)))
				.build();
		this.properties = properties;
		this.normalizer = normalizer;
	}

	@Override
	public String name() {
		return SOURCE_NAME;
	}

	@Override
	public FetchResult fetch(Instant since) {
		List<CandidateArticle> articles = new ArrayList<>();
		boolean failed = false;
		for (RssProperties.Feed feed : properties.feeds()) {
			try {
				articles.addAll(fetchFeed(feed, since));
			}
			catch (Exception ex) {
				log.warn("RSS 피드 수집 실패 (name={}, url={}): {}", feed.name(), feed.url(), ex.toString());
				failed = true;
			}
		}
		return failed ? FetchResult.partial(articles) : FetchResult.of(articles);
	}

	private List<CandidateArticle> fetchFeed(RssProperties.Feed feed, Instant since)
			throws IOException, FeedException {
		byte[] body = restClient.get().uri(feed.url()).retrieve().body(byte[].class);
		if (body == null) {
			return List.of();
		}
		SyndFeed syndFeed = parse(body);
		return syndFeed.getEntries().stream()
				.map(entry -> toCandidate(entry, feed, since))
				.flatMap(Optional::stream)
				.toList();
	}

	/**
	 * {@code allowDoctypes} 기본값(false)을 그대로 둔다. <b>이게 XXE 방어다</b> — true로 바꾸면
	 * 피드가 DTD 외부 엔티티를 선언해 서버의 로컬 파일을 읽어낼 수 있다. 파싱 오류를 만나도
	 * 이 값을 건드려 해결하지 마라.
	 *
	 * <p>{@link XmlReader}로 읽는 이유는 XML 프롤로그의 인코딩 선언을 따르기 위해서다.
	 * 응답 헤더에 charset이 없는 피드를 문자열로 받으면 한글·기호가 깨진다.
	 */
	private static SyndFeed parse(byte[] body) throws IOException, FeedException {
		try (XmlReader reader = new XmlReader(new ByteArrayInputStream(body))) {
			return new SyndFeedInput().build(reader);
		}
	}

	private Optional<CandidateArticle> toCandidate(SyndEntry entry, RssProperties.Feed feed, Instant since) {
		Instant publishedAt = publishedAt(entry);
		if (publishedAt == null || publishedAt.isBefore(since)) {
			return Optional.empty();
		}
		// points 지표가 없는 소스이므로 0.
		return CandidateArticle.of(entry.getTitle(), entry.getLink(), feed.name(), 0, publishedAt, normalizer);
	}

	/** {@code publishedDate}가 없으면 {@code updatedDate}를 쓰고, 둘 다 없으면 그 항목을 버린다. */
	private static Instant publishedAt(SyndEntry entry) {
		Date date = (entry.getPublishedDate() != null) ? entry.getPublishedDate() : entry.getUpdatedDate();
		return (date == null) ? null : date.toInstant();
	}
}
