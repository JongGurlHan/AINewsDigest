package com.example.ainewsdigest.digest;

/**
 * 다이제스트 항목 한 건의 조회용 DTO.
 *
 * <p>엔티티를 도메인 밖으로 내보내지 않기 위한 것이다. {@code open-in-view: false}라 트랜잭션 밖에서
 * {@code Digest.items}를 건드리면 {@code LazyInitializationException}이 나고, 그 경계는 화면과 봇에서
 * 각각 다른 시점에 넘어간다 (CLAUDE.md: Entity를 View 모델로 직접 반환하지 말 것).
 *
 * <p>{@code score}는 담지 않는다. LLM 선별 점수는 임계값 튜닝용 내부 지표이고 아카이브 화면(step 10)에
 * 노출할 값이 아니다.
 */
public record DigestItemView(int position, String titleKo, String summaryKo, String sourceUrl, String sourceDomain) {

	static DigestItemView from(DigestItem item) {
		return new DigestItemView(item.getPosition(), item.getTitleKo(), item.getSummaryKo(),
				item.getSourceUrl(), item.getSourceDomain());
	}
}
