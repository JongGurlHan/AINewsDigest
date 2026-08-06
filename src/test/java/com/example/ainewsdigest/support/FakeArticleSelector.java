package com.example.ainewsdigest.support;

import com.example.ainewsdigest.collect.CandidateArticle;
import com.example.ainewsdigest.curation.ArticleSelector;
import com.example.ainewsdigest.curation.ScoredArticle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * {@link ArticleSelector} 인메모리 페이크. 채점 규칙을 테스트가 함수로 주입한다.
 *
 * <p>포트 계약대로 <b>점수 내림차순, 동점은 입력 순서</b>로 돌려준다. 파이프라인은 이 순서를 믿고
 * 재정렬하지 않으므로 페이크가 순서를 흐트러뜨리면 검증 자체가 무의미해진다.
 */
public class FakeArticleSelector implements ArticleSelector {

	private final ToIntFunction<CandidateArticle> scorer;

	private final List<List<CandidateArticle>> calls = new ArrayList<>();

	private List<String> lastRecentTitles = List.of();

	public FakeArticleSelector(ToIntFunction<CandidateArticle> scorer) {
		this.scorer = scorer;
	}

	/** 모든 후보에 같은 점수를 준다. */
	public static FakeArticleSelector scoringAll(int score) {
		return new FakeArticleSelector((candidate) -> score);
	}

	@Override
	public List<ScoredArticle> scoreAndRank(List<CandidateArticle> candidates, List<String> recentTitles) {
		this.calls.add(List.copyOf(candidates));
		this.lastRecentTitles = List.copyOf(recentTitles);
		List<ScoredArticle> scored = new ArrayList<>();
		for (CandidateArticle candidate : candidates) {
			scored.add(new ScoredArticle(candidate, this.scorer.applyAsInt(candidate), "테스트 채점"));
		}
		// List.sort는 안정 정렬이라 동점이면 입력 순서가 유지된다.
		scored.sort(Comparator.comparingInt(ScoredArticle::score).reversed());
		return List.copyOf(scored);
	}

	public int callCount() {
		return this.calls.size();
	}

	/** 파이프라인이 실제로 채점을 맡긴 후보. 중복 제거·최근 발송분 제외가 반영된 결과다. */
	public List<CandidateArticle> lastCandidates() {
		return this.calls.isEmpty() ? List.of() : this.calls.getLast();
	}

	public List<String> lastRecentTitles() {
		return this.lastRecentTitles;
	}
}
