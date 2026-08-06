package com.example.ainewsdigest.collect;

import java.util.Optional;

/**
 * 기사 본문 추출 아웃바운드 포트. 서비스 계층은 구현체({@code JsoupArticleExtractor})를 직접 참조하지 않는다.
 *
 * <p>HN Algolia는 {@code title}·{@code url}·{@code points}만 주고 본문을 주지 않는다. 제목만으로
 * "3~4문장 한글 요약"을 시키면 모델이 내용을 지어내고, 그 환각이 매일 아침 확신에 찬 문장으로 발송된다 (ADR-006).
 */
public interface ArticleContentExtractor {

	/**
	 * 본문 추출에 성공하면 평문 텍스트, 실패하면 {@code Optional.empty()}. <b>예외를 던지지 않는다.</b>
	 *
	 * <p>페이월·봇 차단·SSRF 차단은 예외 상황이 아니라 정상 흐름이다 (ADR-016). 빈 값이면
	 * 해당 기사만 후보에서 탈락하고 파이프라인은 다음 기사로 넘어간다.
	 */
	Optional<String> extract(String url);
}
