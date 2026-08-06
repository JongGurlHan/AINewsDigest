package com.example.ainewsdigest.curation;

import com.example.ainewsdigest.collect.CandidateArticle;

/**
 * 2차 LLM 호출(요약)의 결과 한 건. step 5가 이걸로 메시지를 조립하고 step 6이 {@code digest_item}으로 저장한다.
 *
 * <p>{@code titleKo}·{@code summaryKo}는 <b>이미 길이가 잘려 있다.</b> 프롬프트의 "40자 이내"는 요청이지
 * 보장이 아니므로 {@code OpenAiArticleSummarizer}가 매핑 시점에 자른다. 여기서 잘라두지 않으면
 * {@code digest_item.title_ko varchar(200)}가 step 6의 마지막 저장에서 터진다.
 *
 * <p>두 값 모두 <b>이스케이프되지 않은 평문</b>이다. LLM이 만든 문자열이므로 HTML로 다루지 마라 —
 * 텔레그램 메시지(step 5)와 웹 화면(step 10)에서 이스케이프하는 것이 프롬프트 인젝션 피해를 가두는 방어선이다.
 */
public record SummarizedArticle(CandidateArticle article, int score, String titleKo, String summaryKo) {
}
