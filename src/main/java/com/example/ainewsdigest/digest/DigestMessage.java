package com.example.ainewsdigest.digest;

import com.example.ainewsdigest.curation.SummarizedArticle;

import java.util.List;

/**
 * 조립이 끝난 텔레그램 메시지 한 통. {@code parse_mode=HTML}로 그대로 보낸다 (ADR-009).
 *
 * <p>{@code includedArticles}는 <b>실제로 이 메시지에 들어간 것</b>이다. 길이 때문에 요약이 잘렸다면
 * 잘린 {@code summaryKo}가 담긴다. step 6이 이 목록을 그대로 {@code digest_item}으로 저장하기 때문에
 * 여기에 원문을 담으면 아카이브 웹페이지가 실제 발송 내용과 다른 글을 보여주게 된다 —
 * 구독자는 잘린 요약을 받았는데 사이트에는 전문이 있는 상태가 된다.
 *
 * @param visibleLength   "after entities parsing" 기준 길이. 태그와 href의 URL은 0자다
 * @param includedCount   {@code includedArticles.size()}와 같다. 호출부가 목록을 펼치지 않고 건수만 볼 때 쓴다
 */
public record DigestMessage(String html, int visibleLength, int includedCount,
		List<SummarizedArticle> includedArticles) {
}
