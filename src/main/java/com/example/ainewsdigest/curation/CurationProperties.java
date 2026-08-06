package com.example.ainewsdigest.curation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 선별·요약의 튜닝 값.
 *
 * <p>{@code minScore}·{@code selectCount}·{@code maxItems}는 <b>파이프라인(step 6)이 읽는다.</b>
 * 선별기·요약기 자체는 건수를 자르지 않는다 — 채점기는 채점만 하고, 몇 점부터 몇 건을 실을지는
 * 오케스트레이션의 판단이다. 점수를 DB에 남겨두면 프롬프트를 건드리지 않고 임계값만 조정해
 * 발송 빈도를 튜닝할 수 있다 (ADR-013).
 *
 * <p>{@code minItems}는 두지 않는다. PRD가 "채택 기준을 넘는 게 적으면 1~2건도 허용"이라고 정했으므로
 * 하한이 없고, 0건은 EMPTY로 처리된다. 쓰이지 않는 키를 남기면 다음 사람이 어딘가 검사가 있다고 착각한다.
 *
 * @param minScore         채택 하한. 이 점수 미만은 쓰지 않는다 (ADR-013)
 * @param selectCount      크롤링에 넘길 건수. 페이월·봇차단 실패를 감안해 필요 건수보다 넉넉히 고른다 (ADR-006)
 * @param maxItems         한 다이제스트의 최대 항목 수
 * @param maxTitleLength   {@code digest_item.title_ko}가 {@code varchar(200)}이다. 넘으면 저장이 터진다
 * @param maxSummaryLength PRD의 건별 상한. 여기서 지켜야 step 5의 길이 계산이 예측 가능해진다
 */
@ConfigurationProperties("ainewsdigest.curation")
public record CurationProperties(Integer minScore, Integer selectCount, Integer maxItems,
		Integer maxTitleLength, Integer maxSummaryLength) {

	private static final int DEFAULT_MIN_SCORE = 3;
	private static final int DEFAULT_SELECT_COUNT = 8;
	private static final int DEFAULT_MAX_ITEMS = 5;
	private static final int DEFAULT_MAX_TITLE_LENGTH = 200;
	private static final int DEFAULT_MAX_SUMMARY_LENGTH = 600;

	public CurationProperties {
		minScore = (minScore == null) ? DEFAULT_MIN_SCORE : minScore;
		selectCount = (selectCount == null) ? DEFAULT_SELECT_COUNT : selectCount;
		maxItems = (maxItems == null) ? DEFAULT_MAX_ITEMS : maxItems;
		maxTitleLength = (maxTitleLength == null) ? DEFAULT_MAX_TITLE_LENGTH : maxTitleLength;
		maxSummaryLength = (maxSummaryLength == null) ? DEFAULT_MAX_SUMMARY_LENGTH : maxSummaryLength;
	}
}
