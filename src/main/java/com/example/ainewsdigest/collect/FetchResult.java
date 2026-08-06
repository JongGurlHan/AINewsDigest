package com.example.ainewsdigest.collect;

import java.util.List;

/**
 * 소스 하나의 수집 결과.
 *
 * <p><b>실패를 빈 리스트로 표현하지 않는다 (ADR-016).</b> 소스가 전부 죽어도 후보는 0건인데,
 * 빈 리스트만 돌려주면 step 6이 이걸 "오늘은 뉴스가 없는 날"로 판정해 EMPTY를 정상 발송하고
 * 헬스체크 핑까지 보낸다 — 관리자도 외부 감시도 장애를 감지하지 못한다.
 * {@code failed} 플래그가 "결과가 없음"과 "실패해서 없음"의 구분을 만든다.
 *
 * @param articles 수집된 후보 (실패해도 부분 결과가 담길 수 있다)
 * @param failed   소스와 통신하지 못한 적이 있으면 true (RSS는 피드 하나만 죽어도 true)
 */
public record FetchResult(List<CandidateArticle> articles, boolean failed) {

	public FetchResult {
		articles = List.copyOf(articles);
	}

	/** 소스와 정상적으로 통신했다. <b>결과가 0건이어도 실패가 아니다.</b> */
	public static FetchResult of(List<CandidateArticle> articles) {
		return new FetchResult(articles, false);
	}

	/** 소스와 통신하지 못했다. */
	public static FetchResult failure() {
		return new FetchResult(List.of(), true);
	}

	/** 일부는 가져왔지만 일부는 실패했다. 부분 실패도 실패다. */
	public static FetchResult partial(List<CandidateArticle> articles) {
		return new FetchResult(articles, true);
	}
}
