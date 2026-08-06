package com.example.ainewsdigest.curation;

import com.example.ainewsdigest.collect.CandidateArticle;

/**
 * 1차 LLM 호출(선별)의 결과 한 건. 제목·출처·points만 보고 매긴 점수다 — 본문은 아직 읽지 않았다 (ADR-007).
 *
 * @param article 채점 대상 후보
 * @param score   1~5점. 3점 이상만 채택된다 (ADR-013)
 * @param reason  채점 근거 한 문장. 임계값을 튜닝할 때 왜 그 점수가 나왔는지 보려고 남긴다
 */
public record ScoredArticle(CandidateArticle article, int score, String reason) {
}
