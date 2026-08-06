package com.example.ainewsdigest.digest;

import com.example.ainewsdigest.collect.ArticleContentExtractor;
import com.example.ainewsdigest.collect.CandidateArticle;
import com.example.ainewsdigest.collect.FetchResult;
import com.example.ainewsdigest.collect.NewsSource;
import com.example.ainewsdigest.curation.ArticleSelector;
import com.example.ainewsdigest.curation.ArticleSummarizer;
import com.example.ainewsdigest.curation.ArticleWithContent;
import com.example.ainewsdigest.curation.CurationProperties;
import com.example.ainewsdigest.curation.ScoredArticle;
import com.example.ainewsdigest.curation.SummarizedArticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 수집 → 중복 제거 → 선별 → 크롤링 → 요약 → 조립 → 저장을 잇는 생성 오케스트레이션
 * (ARCHITECTURE의 "다이제스트 생성" 흐름).
 *
 * <p>새 어댑터를 만들지 않는다. 기존 아웃바운드 포트 네 개({@link NewsSource},
 * {@link ArticleContentExtractor}, {@link ArticleSelector}, {@link ArticleSummarizer})를 조립할 뿐이다.
 *
 * <h2>트랜잭션 경계</h2>
 * <b>{@code generate()}에 {@code @Transactional}을 붙이지 마라.</b> 이 메서드는 HTTP 호출을 수십 번 한다
 * (수집 3소스 + 크롤링 8회 + LLM 2회). 전체를 트랜잭션으로 감싸면 네트워크 대기 중에 DB 커넥션을 수 분간
 * 점유해 커넥션 풀이 마른다 (CLAUDE.md CRITICAL).
 *
 * <p>대신 DB 접근을 리포지토리 호출 단위로 쪼갠다. Spring Data의 {@code SimpleJpaRepository}는
 * 클래스 레벨에 {@code @Transactional}을 달고 있어 <b>조회는 각각 짧은 {@code readOnly} 트랜잭션</b>,
 * 저장은 {@code save()} 한 번이 곧 하나의 짧은 쓰기 트랜잭션이다. {@code Digest}와 {@code DigestItem}은
 * {@code CascadeType.ALL}로 묶여 있어 그 한 번에 원자적으로 들어간다. 여기에 자기 호출용
 * {@code @Transactional} 메서드를 덧붙이는 것은 프록시를 타지 않아 아무 효과가 없다 —
 * 경계를 넓히고 싶다면 별도 빈으로 분리해야 하는데, 저장이 호출 한 번이라 그럴 이유가 없다.
 *
 * <h2>실패 처리</h2>
 * <ul>
 *   <li>{@code NewsSource}는 예외를 던지지 않는다. {@code failed} 플래그를 <b>세어서 결과로 올려보낸다</b> —
 *       여기서 삼키면 소스 3개가 전부 죽은 날도 EMPTY가 정상 발송되고 헬스체크 핑까지 나간다 (ADR-010, ADR-016)</li>
 *   <li>크롤링 실패는 정상 흐름이다. 해당 기사만 탈락시키고 계속 진행한다</li>
 *   <li>선별·요약 실패는 <b>그대로 전파한다.</b> 채점이나 요약이 없으면 그날 다이제스트 자체가 성립하지 않으므로
 *       재시도·알림 판단을 스케줄러(step 11)에 맡긴다 (ADR-016)</li>
 * </ul>
 */
@Service
public class DigestGenerationService {

	private static final Logger log = LoggerFactory.getLogger(DigestGenerationService.class);

	/** 수집 창. 매일 도는 배치이므로 하루치면 충분하다. */
	private static final Duration COLLECT_WINDOW = Duration.ofHours(24);

	/** 중복 판정 기간. 최근 이 기간에 <b>발송된</b> 항목과 대조한다. */
	private static final int DEDUP_DAYS = 7;

	/**
	 * {@code digest_item.source_domain}이 {@code varchar(100)}이다.
	 * {@code CurationProperties}에는 이 값이 없다 — LLM이 만드는 값이 아니라 수집기가 채우는 값이라서다.
	 */
	private static final int MAX_SOURCE_DOMAIN_LENGTH = 100;

	private final List<NewsSource> newsSources;

	private final ArticleContentExtractor contentExtractor;

	private final ArticleSelector articleSelector;

	private final ArticleSummarizer articleSummarizer;

	private final DigestMessageBuilder messageBuilder;

	private final DigestRepository digests;

	private final CurationProperties curation;

	public DigestGenerationService(List<NewsSource> newsSources, ArticleContentExtractor contentExtractor,
			ArticleSelector articleSelector, ArticleSummarizer articleSummarizer,
			DigestMessageBuilder messageBuilder, DigestRepository digests, CurationProperties curation) {
		this.newsSources = List.copyOf(newsSources);
		this.contentExtractor = contentExtractor;
		this.articleSelector = articleSelector;
		this.articleSummarizer = articleSummarizer;
		this.messageBuilder = messageBuilder;
		this.digests = digests;
		this.curation = curation;
	}

	/**
	 * 해당 날짜의 다이제스트를 생성해 PENDING(또는 EMPTY) 상태로 저장한다. <b>발송하지 않는다</b> — step 9의 몫이다.
	 *
	 * <p>이미 그날 다이제스트가 있으면 아무것도 하지 않는다. 07:15 재시도(ARCHITECTURE "세 시각의 역할")가
	 * 07:00 성공분을 덮어쓰지 않게 하는 장치이며, {@code digest_date} UNIQUE가 최종 방어선이다.
	 * 존재 확인을 {@code existsByDigestDate}가 아니라 {@code findByDigestDate}로 하는 이유는
	 * 판정과 동시에 그날 상태를 결과에 담아야 하기 때문이다. 두 번 조회하면 그 사이 값이 사라진 경우를
	 * 또 다뤄야 하는데, 멱등성 판정으로서는 완전히 동일하다.
	 */
	public GenerationResult generate(LocalDate date) {
		Optional<Digest> existing = this.digests.findByDigestDate(date);
		if (existing.isPresent()) {
			log.info("{} 다이제스트가 이미 있다(status={}). 아무것도 하지 않는다.", date, existing.get().getStatus());
			return new GenerationResult(existing.get().getStatus(), 0, 0, 0);
		}

		Instant now = Instant.now();
		Collected collected = collect(now.minus(COLLECT_WINDOW));
		List<CandidateArticle> candidates = deduplicate(collected.articles(), date);
		int candidateCount = candidates.size();
		int failedSourceCount = collected.failedSources();

		if (candidates.isEmpty()) {
			return saveEmpty(date, now, candidateCount, failedSourceCount);
		}

		List<ScoredArticle> selected = select(candidates, date);
		List<ArticleWithContent> finalists = crawl(selected);
		if (finalists.isEmpty()) {
			return saveEmpty(date, now, candidateCount, failedSourceCount);
		}

		List<SummarizedArticle> summarized = this.articleSummarizer.summarize(finalists);
		DigestMessage message = this.messageBuilder.build(date, summarized);
		if (message.includedArticles().isEmpty()) {
			// 요약기가 한 건도 돌려주지 않은 날. 조립기는 이미 "뉴스 없음" 메시지를 만들어 뒀다.
			log.warn("요약 결과가 0건이라 EMPTY로 저장한다 (크롤링 성공 {}건)", finalists.size());
			return saveEmpty(date, now, candidateCount, failedSourceCount);
		}
		return save(date, now, message, candidateCount, failedSourceCount);
	}

	/**
	 * 모든 소스에서 후보를 모으고 <b>실패한 소스의 수를 센다.</b>
	 *
	 * <p>{@code try-catch}를 하지 않는 것은 포트가 예외를 던지지 않기로 계약했기 때문이다 (ADR-016).
	 * 실패는 {@link FetchResult#failed()}로 오고, 그 수는 {@link GenerationResult}로 그대로 올라간다.
	 * 판단(전면 실패인가 일부 장애인가)은 여기가 아니라 스케줄러가 한다.
	 */
	private Collected collect(Instant since) {
		List<CandidateArticle> articles = new ArrayList<>();
		int failedSources = 0;
		for (NewsSource source : this.newsSources) {
			FetchResult result = source.fetch(since);
			if (result.failed()) {
				failedSources++;
				log.warn("수집 소스 실패: {} (부분 결과 {}건)", source.name(), result.articles().size());
			}
			articles.addAll(result.articles());
		}
		log.info("수집 완료: {}건 (실패 소스 {}/{})", articles.size(), failedSources, this.newsSources.size());
		return new Collected(articles, failedSources);
	}

	/**
	 * 후보 내 중복({@code normalizedUrl} 기준)을 없애고, 최근 7일간 <b>발송된</b> 항목과 겹치는 것을 뺀다.
	 *
	 * <p>{@code LinkedHashMap}으로 첫 등장을 남긴다. 같은 기사가 HN과 RSS 양쪽에 있으면 먼저 온 소스의
	 * 값을 쓴다 — 어느 쪽이든 무방하지만 순서가 실행마다 달라지면 재현이 안 된다.
	 */
	private List<CandidateArticle> deduplicate(List<CandidateArticle> collected, LocalDate date) {
		Set<String> alreadySent = new HashSet<>(this.digests.findNormalizedUrlsSince(date.minusDays(DEDUP_DAYS)));
		Map<String, CandidateArticle> unique = new LinkedHashMap<>();
		for (CandidateArticle candidate : collected) {
			if (alreadySent.contains(candidate.normalizedUrl())) {
				continue;
			}
			unique.putIfAbsent(candidate.normalizedUrl(), candidate);
		}
		log.info("중복 제거: 수집 {}건 -> 후보 {}건 (최근 {}일 발송분 {}건과 대조)",
				collected.size(), unique.size(), DEDUP_DAYS, alreadySent.size());
		return List.copyOf(unique.values());
	}

	/**
	 * 1차 LLM 호출로 채점한 뒤 {@code minScore} 이상만 남기고 상위 {@code selectCount}건을 취한다.
	 *
	 * <p>{@code scoreAndRank}는 점수 내림차순을 계약으로 보장하므로 여기서 다시 정렬하지 않는다.
	 * 재정렬하면 계약 위반이 조용히 덮여 어느 쪽이 틀렸는지 알 수 없게 된다.
	 */
	private List<ScoredArticle> select(List<CandidateArticle> candidates, LocalDate date) {
		List<String> recentTitles = this.digests.findTitlesSince(date.minusDays(DEDUP_DAYS));
		List<ScoredArticle> scored = this.articleSelector.scoreAndRank(candidates, recentTitles);
		List<ScoredArticle> selected = scored.stream()
				.filter((article) -> article.score() >= this.curation.minScore())
				.limit(this.curation.selectCount())
				.toList();
		log.info("선별: 후보 {}건 채점 -> {}점 이상 {}건 -> 크롤링 대상 {}건",
				candidates.size(), this.curation.minScore(),
				scored.stream().filter((article) -> article.score() >= this.curation.minScore()).count(),
				selected.size());
		return selected;
	}

	/**
	 * 선별을 통과한 것만 본문을 받아온다. <b>선별 전에 크롤링하지 마라</b> — 크롤링 횟수를 줄이는 것이
	 * 2단계 분리의 목적이다 (ADR-007).
	 *
	 * <p>추출 실패는 예외가 아니라 {@code Optional.empty()}다. 페이월·봇 차단·SSRF 차단은 매일 일어나는
	 * 정상 흐름이므로 해당 기사만 탈락시키고 넘어간다. 넉넉히(8건) 고른 이유가 이것이다 (ADR-006).
	 */
	private List<ArticleWithContent> crawl(List<ScoredArticle> selected) {
		List<ArticleWithContent> extracted = new ArrayList<>();
		for (ScoredArticle article : selected) {
			Optional<String> content = this.contentExtractor.extract(article.article().url());
			if (content.isEmpty()) {
				log.info("본문 확보 실패로 탈락: {}", article.article().url());
				continue;
			}
			extracted.add(new ArticleWithContent(article.article(), article.score(), content.get()));
		}
		int limit = Math.min(this.curation.maxItems(), extracted.size());
		log.info("크롤링: {}건 시도 -> {}건 성공 -> 상위 {}건 요약", selected.size(), extracted.size(), limit);
		return List.copyOf(extracted.subList(0, limit));
	}

	/**
	 * 채택된 기사가 0건인 날. <b>EMPTY는 "끝난 것"이 아니라 발송 대상이다</b> —
	 * {@code sentAt}이 비어 있으므로 07:30에 그대로 나간다. 침묵하지 않는다는 것이 PRD의 약속이다.
	 *
	 * <p>본문은 조립기가 만든다. "뉴스 없음" 문구를 여기서 따로 붙이면 헤더·날짜 표기가 발송분과 어긋난다.
	 */
	private GenerationResult saveEmpty(LocalDate date, Instant now, int candidateCount, int failedSourceCount) {
		DigestMessage message = this.messageBuilder.build(date, List.of());
		this.digests.save(Digest.empty(date, message.html(), now));
		log.info("{} 채택 기사 0건 -> EMPTY 저장 (후보 {}건, 실패 소스 {}건)",
				date, candidateCount, failedSourceCount);
		return new GenerationResult(DigestStatus.EMPTY, 0, candidateCount, failedSourceCount);
	}

	/**
	 * PENDING 다이제스트와 항목들을 저장한다.
	 *
	 * <p><b>{@code summarized}가 아니라 {@link DigestMessage#includedArticles()}로 만든다.</b>
	 * 조립기는 길이 때문에 항목을 빼기도 하고 마지막 항목의 요약을 자르기도 한다. 요약기 결과를 그대로
	 * 저장하면 구독자는 잘린 요약을 받았는데 아카이브에는 전문이 남는다.
	 *
	 * <p>저장 직전에 컬럼 폭 기준으로 한 번 더 자른다. step 2와 step 4가 각자 막고 있지만, 이 저장이
	 * 실패하면 <b>LLM 비용을 전부 지불한 뒤에</b> 그날 다이제스트가 통째로 사라진다. 마지막 관문 하나의 값이 싸다.
	 */
	private GenerationResult save(LocalDate date, Instant now, DigestMessage message,
			int candidateCount, int failedSourceCount) {
		Digest digest = Digest.pending(date, message.html(), now);
		List<SummarizedArticle> included = message.includedArticles();
		for (int index = 0; index < included.size(); index++) {
			SummarizedArticle article = included.get(index);
			digest.addItem(new DigestItem(index + 1,
					cut(article.titleKo(), this.curation.maxTitleLength(), "title_ko"),
					article.summaryKo(),
					article.article().url(),
					article.article().normalizedUrl(),
					cut(article.article().sourceDomain(), MAX_SOURCE_DOMAIN_LENGTH, "source_domain"),
					article.score()));
		}
		this.digests.save(digest);
		log.info("{} 다이제스트 저장 완료: {}건, 메시지 {}자 (후보 {}건, 실패 소스 {}건)",
				date, included.size(), message.visibleLength(), candidateCount, failedSourceCount);
		return new GenerationResult(DigestStatus.PENDING, included.size(), candidateCount, failedSourceCount);
	}

	/**
	 * 코드포인트 경계에서 자른다. {@code substring}으로 char 수를 세어 자르면 이모지의 UTF-16
	 * 서로게이트 페어가 반으로 쪼개지고, 깨진 문자가 그대로 DB와 아카이브 화면에 남는다.
	 *
	 * <p>PostgreSQL의 {@code varchar(n)}은 문자(코드포인트) 수를 센다. 그래서 코드포인트 기준 절단이
	 * 컬럼 폭과 정확히 맞물린다.
	 */
	private static String cut(String text, int maxLength, String column) {
		int codePoints = text.codePointCount(0, text.length());
		if (codePoints <= maxLength) {
			return text;
		}
		log.warn("{} 컬럼 폭을 넘어 저장 직전에 잘랐다 ({}자 -> {}자)", column, codePoints, maxLength);
		return text.substring(0, text.offsetByCodePoints(0, maxLength));
	}

	/** 수집 단계의 중간 결과. 실패한 소스 수를 후보와 함께 들고 다녀야 아래로 흘려보낼 수 있다. */
	private record Collected(List<CandidateArticle> articles, int failedSources) {
	}

	/**
	 * 생성 결과. 스케줄러(step 11)가 이 값으로 관리자 알림 여부를 판정한다.
	 *
	 * <p><b>{@code failedSourceCount}를 빼지 마라.</b> 수집 소스가 전부 죽어도 후보는 0건이라
	 * {@code candidateCount}만으로는 "진짜 조용한 날"과 구분되지 않는다. 그대로 두면 EMPTY가 정상 발송되고
	 * 헬스체크 핑까지 나가 ADR-010의 감시가 통째로 무력화된다.
	 *
	 * @param status            그날 다이제스트의 상태. 이미 있던 날이면 기존 값이다
	 * @param itemCount         이번 실행이 저장한 {@code DigestItem} 수
	 * @param candidateCount    중복 제거까지 마치고 선별에 넘긴 후보 수 (이번 실행 기준)
	 * @param failedSourceCount {@code FetchResult.failed()}가 true였던 소스 수 (이번 실행 기준)
	 */
	public record GenerationResult(DigestStatus status, int itemCount, int candidateCount, int failedSourceCount) {
	}
}
