package com.example.ainewsdigest.collect;

import java.time.Instant;
import java.util.Optional;

/**
 * 수집 단계의 후보 기사. 선별(step 4) 전이므로 DB에 저장하지 않는다.
 *
 * <p>수집기는 <b>외부에서 들어온 값이 파이프라인에 처음 닿는 지점</b>이다. 여기서 거르지 않으면
 * 나머지 전 구간이 그 값을 신뢰한다. 그래서 검증을 {@link #of} 한 곳에 모았다.
 *
 * <h2>{@code content}는 왜 있나</h2>
 * 원칙은 "후보는 메타데이터만 나르고 본문은 크롤링이 URL에서 얻는다"이다. 그런데 수집 시점에 이미
 * 본문을 손에 쥐는 소스가 있다 — {@code ChangelogClient}는 마크다운 전문을 받아 버전 섹션까지 파싱한다.
 * 그 URL은 raw {@code .md}({@code text/plain})라 {@code JsoupArticleExtractor}가 HTML이 아니라며
 * <b>반드시</b> 거절한다. 즉 본문을 실어 보내지 않으면 이 소스는 선별까지 올라가 LLM 채점 비용만 쓰고
 * 크롤링에서 100% 탈락한다 — 한 번도 발송될 수 없다.
 *
 * <p>그래서 예외를 값으로 만들었다. {@code content}가 있으면 파이프라인이 크롤링을 건너뛴다.
 * 없으면(대부분의 소스) 지금까지와 완전히 같다.
 */
public record CandidateArticle(
		String title,
		String url,
		String normalizedUrl,
		String sourceDomain,
		String sourceName,
		int points,
		Instant publishedAt,
		String content) {

	/**
	 * {@code digest_item.source_domain}이 {@code varchar(100)}인데 FQDN은 최대 253자다.
	 * 넘치는 값을 통과시키면 step 6의 마지막 저장에서 {@code DataIntegrityViolationException}이 나고,
	 * LLM 호출 비용을 전부 지불한 뒤 그날 다이제스트가 통째로 날아간다.
	 */
	private static final int MAX_SOURCE_DOMAIN_LENGTH = 100;

	/**
	 * 어댑터는 반드시 이 팩토리로만 후보를 만든다. 정규화 필드를 손으로 채우지 마라 —
	 * 어댑터 3개가 각자 정규화하면 하나만 빠뜨려도 조용히 깨진다. 그것도 나쁜 쪽으로:
	 * {@code normalizedUrl}이 빈 문자열이면 서로 다른 기사가 전부 같은 값이 되어 중복 제거에 몰살당하고,
	 * 매일 아침 EMPTY 다이제스트가 나간다.
	 *
	 * <p>아래 중 하나라도 걸리면 {@code Optional.empty()}다 (후보 탈락):
	 * <ul>
	 *   <li>{@code url}의 스킴이 http/https가 아니다 — {@code javascript:} URL이 step 10의
	 *       {@code <a th:href>}로 렌더링된다. {@code th:href}는 {@code th:text}와 달리 스킴을 막지 않는다</li>
	 *   <li>{@code title}이 비어 있다</li>
	 *   <li>{@code sourceDomain}이 100자를 넘는다</li>
	 * </ul>
	 *
	 * <p>여기서 탈락하는 것은 <b>소스 장애가 아니다.</b> 값이 이상해서 버린 것이므로
	 * {@link FetchResult#failed()}를 세우지 않는다.
	 */
	public static Optional<CandidateArticle> of(String title, String url, String sourceName,
			int points, Instant publishedAt, UrlNormalizer normalizer) {
		return of(title, url, sourceName, points, publishedAt, normalizer, null);
	}

	/**
	 * 본문을 이미 확보한 소스용 팩토리. 실린 본문은 크롤링을 <b>대체</b>한다.
	 *
	 * <p>{@code content}가 비어 있으면 없는 것으로 취급한다. 빈 문자열을 그대로 두면 파이프라인이
	 * "본문 있음"으로 보고 크롤링을 건너뛴 채 빈 본문을 요약기에 넘긴다 — 모델이 제목만 보고
	 * 릴리즈 내용을 지어낸다. 그게 매일 아침 확신에 찬 문장으로 발송된다 (ADR-006).
	 */
	public static Optional<CandidateArticle> of(String title, String url, String sourceName,
			int points, Instant publishedAt, UrlNormalizer normalizer, String content) {
		if (title == null || title.isBlank()) {
			return Optional.empty();
		}
		if (!normalizer.isHttpUrl(url)) {
			return Optional.empty();
		}
		String sourceDomain = normalizer.extractDomain(url);
		if (sourceDomain.length() > MAX_SOURCE_DOMAIN_LENGTH) {
			return Optional.empty();
		}
		return Optional.of(new CandidateArticle(title.trim(), url.trim(), normalizer.normalize(url),
				sourceDomain, sourceName, points, publishedAt,
				(content == null || content.isBlank()) ? null : content));
	}

	/** 크롤링을 건너뛰어도 되는가. 호출부가 {@code content() != null}을 반복하지 않게 한다. */
	public boolean hasContent() {
		return this.content != null;
	}
}
