package com.example.ainewsdigest.curation;

import com.example.ainewsdigest.collect.CandidateArticle;

/**
 * 2차 LLM 호출(요약)의 입력 한 건. 선별을 통과하고 본문 크롤링까지 성공한 기사만 여기까지 온다.
 *
 * @param article 후보 기사
 * @param score   1차 선별 점수. 요약 후에도 그대로 들고 가야 {@code digest_item.score}에 남는다
 * @param content {@code ArticleContentExtractor}가 뽑은 평문 본문.
 *                <b>공격자가 내용을 정할 수 있는 입력이다</b> — HN에 링크를 올리면 크롤링된다
 */
public record ArticleWithContent(CandidateArticle article, int score, String content) {
}
