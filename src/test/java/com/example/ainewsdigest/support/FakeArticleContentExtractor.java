package com.example.ainewsdigest.support;

import com.example.ainewsdigest.collect.ArticleContentExtractor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ArticleContentExtractor} 인메모리 페이크. 기본은 성공이고 지정한 URL만 실패한다
 * (페이월·봇 차단·SSRF 차단은 정상 흐름이므로 예외가 아니라 {@code Optional.empty()}다 — ADR-016).
 *
 * <p>요청받은 URL을 순서대로 기록한다. <b>몇 번 호출됐는지가 ADR-007의 검증 지점이다</b> —
 * 선별 전에 크롤링하면 호출 횟수가 후보 전체로 늘어난다.
 */
public class FakeArticleContentExtractor implements ArticleContentExtractor {

	private final Set<String> failingUrls = new HashSet<>();

	private final List<String> requestedUrls = new ArrayList<>();

	/** 이 URL들은 본문 확보에 실패한다. */
	public FakeArticleContentExtractor failFor(String... urls) {
		this.failingUrls.addAll(List.of(urls));
		return this;
	}

	@Override
	public Optional<String> extract(String url) {
		this.requestedUrls.add(url);
		if (this.failingUrls.contains(url)) {
			return Optional.empty();
		}
		return Optional.of("본문 텍스트: " + url);
	}

	public List<String> requestedUrls() {
		return List.copyOf(this.requestedUrls);
	}

	public int callCount() {
		return this.requestedUrls.size();
	}
}
