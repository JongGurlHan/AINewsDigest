package com.example.ainewsdigest.digest;

import com.example.ainewsdigest.collect.CandidateArticle;
import com.example.ainewsdigest.collect.NewsSource;
import com.example.ainewsdigest.collect.UrlNormalizer;
import com.example.ainewsdigest.curation.ArticleSelector;
import com.example.ainewsdigest.curation.CurationProperties;
import com.example.ainewsdigest.digest.DigestGenerationService.GenerationResult;
import com.example.ainewsdigest.support.FakeArticleContentExtractor;
import com.example.ainewsdigest.support.FakeArticleSelector;
import com.example.ainewsdigest.support.FakeArticleSummarizer;
import com.example.ainewsdigest.support.FakeNewsSource;
import com.example.ainewsdigest.support.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 생성 파이프라인 통합 테스트. <b>아웃바운드 포트는 전부 인메모리 페이크</b>이고 DB만 실제
 * PostgreSQL(Testcontainers)이다 — 컬럼 폭 절단처럼 실제 INSERT가 나야만 검증되는 것이 있다 (ADR-011).
 *
 * <p>서비스를 컨텍스트에서 주입받지 않고 손으로 조립한다. {@code List<NewsSource>} 주입은 타입에 맞는
 * 빈을 <b>전부</b> 모으므로, 페이크를 빈으로 추가해도 실제 어댑터 3개가 그대로 함께 들어온다.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class DigestGenerationServiceTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 8, 6);

	private static final Instant PAST = Instant.parse("2026-08-03T22:00:00Z");

	@Autowired
	private DigestRepository digests;

	@PersistenceContext
	private EntityManager entityManager;

	private final UrlNormalizer normalizer = new UrlNormalizer();

	private final CurationProperties curation = new CurationProperties(null, null, null, null, null);

	private final FakeArticleContentExtractor extractor = new FakeArticleContentExtractor();

	private final FakeArticleSummarizer summarizer = new FakeArticleSummarizer();

	private final DigestMessageBuilder messageBuilder = new DigestMessageBuilder(new MessageProperties(null, null));

	/** 후보 20건 → 3점 이상 10건 → 상위 8건만 크롤링 → 상위 5건 저장. */
	@Test
	void savesTopItemsWhenTheWholePipelineSucceeds() {
		List<CandidateArticle> candidates = candidates(20);
		FakeArticleSelector selector = new FakeArticleSelector((candidate) -> (candidate.points() < 10) ? 5 : 2);

		GenerationResult result = service(sources(candidates), selector).generate(TODAY);

		assertEquals(DigestStatus.PENDING, result.status());
		assertEquals(5, result.itemCount());
		assertEquals(20, result.candidateCount());
		assertEquals(0, result.failedSourceCount());
		// 채택 기준을 넘은 10건 중 selectCount(8)건만 크롤링한다 (ADR-007).
		assertEquals(8, this.extractor.callCount());
		assertEquals(5, this.summarizer.lastArticles().size());

		Digest saved = reload();
		assertEquals(DigestStatus.PENDING, saved.getStatus());
		assertEquals(5, saved.getItems().size());
		assertEquals(List.of(1, 2, 3, 4, 5), saved.getItems().stream().map(DigestItem::getPosition).toList());
		assertEquals(candidates.getFirst().url(), saved.getItems().getFirst().getSourceUrl());
		assertEquals(candidates.getFirst().normalizedUrl(), saved.getItems().getFirst().getNormalizedUrl());
		assertEquals(5, saved.getItems().getFirst().getScore());
		// 생성만 하고 발송은 하지 않는다. sentAt이 비어 있어야 07:30 발송 대상으로 남는다.
		assertNull(saved.getSentAt());
	}

	/** 크롤링 실패는 예외가 아니라 정상 흐름이다. 남은 3건으로도 다이제스트는 성립한다. */
	@Test
	void buildsDigestFromSurvivorsWhenSomeCrawlsFail() {
		List<CandidateArticle> candidates = candidates(8);
		this.extractor.failFor(candidates.subList(3, 8).stream().map(CandidateArticle::url).toArray(String[]::new));

		GenerationResult result = service(sources(candidates), FakeArticleSelector.scoringAll(5)).generate(TODAY);

		assertEquals(DigestStatus.PENDING, result.status());
		assertEquals(3, result.itemCount());
		assertEquals(8, this.extractor.callCount());
		assertEquals(3, reload().getItems().size());
	}

	/** 채택 기준(3점)을 넘는 후보가 없으면 EMPTY. 크롤링도 요약도 하지 않는다. */
	@Test
	void savesEmptyDigestWhenNoCandidateReachesTheThreshold() {
		GenerationResult result = service(sources(candidates(5)), FakeArticleSelector.scoringAll(2)).generate(TODAY);

		assertEquals(DigestStatus.EMPTY, result.status());
		assertEquals(0, result.itemCount());
		assertEquals(5, result.candidateCount());
		assertEquals(0, result.failedSourceCount());
		assertEquals(0, this.extractor.callCount());
		assertEquals(0, this.summarizer.callCount());

		Digest saved = reload();
		assertEquals(DigestStatus.EMPTY, saved.getStatus());
		assertTrue(saved.getItems().isEmpty());
		assertTrue(saved.getMessageText().contains("오늘의 AI 뉴스는 없습니다."), saved.getMessageText());
		// EMPTY는 "끝난 것"이 아니라 발송 대상이다. 침묵하지 않는다 (PRD, ADR-013).
		assertNull(saved.getSentAt());
	}

	/** 같은 날짜로 두 번 호출해도 두 번째는 외부 포트를 한 번도 건드리지 않는다 (07:15 재시도가 07:00분을 덮지 않는다). */
	@Test
	void secondRunForTheSameDateTouchesNothing() {
		FakeNewsSource source = FakeNewsSource.returning("hn", candidates(8));
		FakeArticleSelector selector = FakeArticleSelector.scoringAll(5);
		DigestGenerationService service = service(List.of(source), selector);
		service.generate(TODAY);
		int itemsAfterFirstRun = reload().getItems().size();

		GenerationResult second = service.generate(TODAY);

		assertEquals(DigestStatus.PENDING, second.status());
		assertEquals(0, second.itemCount());
		assertEquals(0, second.candidateCount());
		assertEquals(0, second.failedSourceCount());
		assertEquals(1, source.callCount());
		assertEquals(1, selector.callCount());
		assertEquals(1, this.summarizer.callCount());
		assertEquals(8, this.extractor.callCount());
		assertEquals(itemsAfterFirstRun, reload().getItems().size());
	}

	/** 최근 7일 안에 발송된 URL은 선별기까지 가지도 않는다. 제목은 중복 배제용으로 넘어간다. */
	@Test
	void dropsCandidatesAlreadySentWithinTheLastSevenDays() {
		List<CandidateArticle> candidates = candidates(3);
		saveSentDigest(TODAY.minusDays(3), "이미 나간 제목", candidates.get(1).normalizedUrl());
		FakeArticleSelector selector = FakeArticleSelector.scoringAll(5);

		service(sources(candidates), selector).generate(TODAY);

		assertEquals(List.of(candidates.get(0).normalizedUrl(), candidates.get(2).normalizedUrl()),
				selector.lastCandidates().stream().map(CandidateArticle::normalizedUrl).toList());
		assertEquals(List.of("이미 나간 제목"), selector.lastRecentTitles());
	}

	/** 3점 미만은 크롤링 대상에서 빠진다. 크롤링 횟수를 줄이는 것이 2단계 분리의 목적이다 (ADR-007). */
	@Test
	void neverCrawlsCandidatesBelowMinScore() {
		List<CandidateArticle> candidates = candidates(6);
		FakeArticleSelector selector = new FakeArticleSelector((candidate) -> (candidate.points() < 2) ? 4 : 1);

		service(sources(candidates), selector).generate(TODAY);

		assertEquals(2, this.extractor.callCount());
		assertEquals(List.of(candidates.get(0).url(), candidates.get(1).url()), this.extractor.requestedUrls());
	}

	/**
	 * 수집이 통째로 죽은 날. 결과는 EMPTY지만 {@code failedSourceCount}가 서 있어야
	 * 스케줄러가 "조용한 날"과 구분해 관리자에게 알릴 수 있다 (ADR-010, ADR-016).
	 */
	@Test
	void reportsFailedSourcesWhenEveryCollectorIsDown() {
		List<NewsSource> allDown = List.of(FakeNewsSource.failing("hn"), FakeNewsSource.failing("rss"),
				FakeNewsSource.failing("changelog"));

		GenerationResult result = service(allDown, FakeArticleSelector.scoringAll(5)).generate(TODAY);

		assertEquals(DigestStatus.EMPTY, result.status());
		assertEquals(0, result.candidateCount());
		assertEquals(3, result.failedSourceCount());
		assertEquals(DigestStatus.EMPTY, reload().getStatus());
	}

	/** 소스는 멀쩡한데 결과가 0건인 진짜 조용한 날. 위 케이스와 반드시 구분되어야 한다. */
	@Test
	void reportsNoFailedSourcesOnAGenuinelyQuietDay() {
		List<NewsSource> healthy = List.of(FakeNewsSource.returning("hn", List.of()),
				FakeNewsSource.returning("rss", List.of()));

		GenerationResult result = service(healthy, FakeArticleSelector.scoringAll(5)).generate(TODAY);

		assertEquals(DigestStatus.EMPTY, result.status());
		assertEquals(0, result.candidateCount());
		assertEquals(0, result.failedSourceCount());
	}

	/** {@code title_ko}가 varchar(200)이다. 요약기가 300자를 돌려줘도 저장이 터지면 안 된다. */
	@Test
	void truncatesOverlongTitleToTheColumnWidth() {
		this.summarizer.withTitle((article) -> "가".repeat(300));

		service(sources(candidates(1)), FakeArticleSelector.scoringAll(5)).generate(TODAY);

		assertEquals(200, reload().getItems().getFirst().getTitleKo().length());
	}

	/**
	 * 조립기가 길이 때문에 건수를 줄이고 요약을 잘랐다면 <b>저장분도 잘려 있어야</b> 한다.
	 * 아니면 구독자는 잘린 요약을 받았는데 아카이브에는 전문이 남는다.
	 */
	@Test
	void storesExactlyWhatTheBuilderPutIntoTheMessage() {
		String longSummary = "요".repeat(1200);
		this.summarizer.withSummary((article) -> longSummary);
		DigestMessageBuilder tightBuilder = new DigestMessageBuilder(new MessageProperties(300, null));

		GenerationResult result = service(sources(candidates(3)), FakeArticleSelector.scoringAll(5), tightBuilder)
				.generate(TODAY);

		assertEquals(1, result.itemCount());
		Digest saved = reload();
		assertEquals(1, saved.getItems().size());
		String stored = saved.getItems().getFirst().getSummaryKo();
		assertTrue(stored.length() < longSummary.length(), "요약이 잘리지 않았다: " + stored.length());
		assertTrue(longSummary.startsWith(stored), "잘린 요약이 원문의 앞부분이 아니다");
	}

	private DigestGenerationService service(List<NewsSource> newsSources, ArticleSelector selector) {
		return service(newsSources, selector, this.messageBuilder);
	}

	private DigestGenerationService service(List<NewsSource> newsSources, ArticleSelector selector,
			DigestMessageBuilder builder) {
		return new DigestGenerationService(newsSources, this.extractor, selector, this.summarizer, builder,
				this.digests, this.curation);
	}

	private static List<NewsSource> sources(List<CandidateArticle> articles) {
		return List.of(FakeNewsSource.returning("hn", articles));
	}

	/** {@code points}에 인덱스를 심어 둔다. 채점 함수가 "몇 번째 후보인가"로 점수를 나눌 수 있게 하기 위해서다. */
	private List<CandidateArticle> candidates(int count) {
		return IntStream.range(0, count)
				.mapToObj((index) -> CandidateArticle.of("Article " + index,
						"https://example.com/article-" + index, "HN", index, PAST, this.normalizer).orElseThrow())
				.toList();
	}

	private void saveSentDigest(LocalDate date, String titleKo, String normalizedUrl) {
		Digest digest = Digest.pending(date, "지난 본문", PAST);
		digest.addItem(new DigestItem(1, titleKo, "지난 요약", "https://example.com/past", normalizedUrl,
				"example.com", 4));
		digest.markSent(PAST);
		this.digests.save(digest);
		this.entityManager.flush();
	}

	/** flush로 실제 INSERT를 강제하고 clear로 1차 캐시를 비운다. 컬럼 폭 절단은 DB까지 가야 검증된다. */
	private Digest reload() {
		this.entityManager.flush();
		this.entityManager.clear();
		return this.digests.findByDigestDate(TODAY).orElseThrow();
	}
}
