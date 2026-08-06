package com.example.ainewsdigest.curation;

import com.example.ainewsdigest.collect.CandidateArticle;

import java.util.List;

/**
 * 후보 채점·선별 아웃바운드 포트. 서비스 계층은 구현체({@code OpenAiArticleSelector})를 직접 참조하지 않는다.
 *
 * <p>본문을 읽기 <b>전에</b> 제목·출처·points만으로 채점한다. 크롤링은 파이프라인에서 가장 느리고 가장 잘
 * 깨지는 구간이라, 선별을 먼저 하면 크롤링 횟수가 절반 이하로 줄어든다 (ADR-007).
 */
public interface ArticleSelector {

	/**
	 * 후보를 1~5점으로 채점하고 <b>점수 내림차순</b>으로 반환한다. 동점이면 입력 순서를 유지한다.
	 *
	 * <p><b>실패하면 예외를 던진다.</b> 수집·발송과 달리 선별 실패는 부분 실패가 아니다 — 채점이 없으면
	 * 그날 다이제스트 자체가 성립하지 않으므로, 호출자(스케줄러)까지 올려보내 재시도·알림을 결정하게 한다 (ADR-016).
	 *
	 * @param candidates   중복 제거를 마친 후보. 비어 있으면 LLM을 호출하지 않고 빈 리스트를 돌려준다
	 * @param recentTitles 최근 7일간 발송한 한글 제목. 같은 사건의 재발송을 막는 데 쓴다
	 */
	List<ScoredArticle> scoreAndRank(List<CandidateArticle> candidates, List<String> recentTitles);
}
