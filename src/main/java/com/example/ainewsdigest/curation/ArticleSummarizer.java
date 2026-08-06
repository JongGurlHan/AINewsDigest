package com.example.ainewsdigest.curation;

import java.util.List;

/**
 * 한글 요약 아웃바운드 포트. 서비스 계층은 구현체({@code OpenAiArticleSummarizer})를 직접 참조하지 않는다.
 */
public interface ArticleSummarizer {

	/**
	 * 본문이 확보된 기사를 한글 제목·요약으로 바꾼다. 길이 상한은 반환 전에 <b>코드로 잘라</b> 보장한다.
	 *
	 * <p><b>실패하면 예외를 던진다</b> ({@link ArticleSelector#scoreAndRank}와 같은 이유, ADR-016).
	 * 다만 응답에 일부 기사가 빠져 온 것은 실패가 아니다 — 온 것만 담아 돌려주고, 몇 건을 실을지는 step 6이 정한다.
	 *
	 * @param articles 선별을 통과하고 본문 크롤링에 성공한 기사. 비어 있으면 LLM을 호출하지 않는다
	 */
	List<SummarizedArticle> summarize(List<ArticleWithContent> articles);
}
