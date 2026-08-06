package com.example.ainewsdigest.support;

import com.example.ainewsdigest.collect.CandidateArticle;
import com.example.ainewsdigest.collect.FetchResult;
import com.example.ainewsdigest.collect.NewsSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link NewsSource} 인메모리 페이크. <b>실패를 빈 리스트로 표현하지 않는다</b> — 실제 어댑터와 같이
 * {@link FetchResult#failed()}로 구분해야 "결과가 없음"과 "실패해서 없음"을 나누는 로직을 검증할 수 있다 (ADR-016).
 */
public class FakeNewsSource implements NewsSource {

	private final String name;

	private final FetchResult result;

	private final List<Instant> calls = new ArrayList<>();

	private FakeNewsSource(String name, FetchResult result) {
		this.name = name;
		this.result = result;
	}

	/** 정상 응답. 0건이어도 실패가 아니다. */
	public static FakeNewsSource returning(String name, List<CandidateArticle> articles) {
		return new FakeNewsSource(name, FetchResult.of(articles));
	}

	/** 소스와 통신하지 못했다. */
	public static FakeNewsSource failing(String name) {
		return new FakeNewsSource(name, FetchResult.failure());
	}

	/** 일부는 가져왔지만 일부는 실패했다. */
	public static FakeNewsSource partial(String name, List<CandidateArticle> articles) {
		return new FakeNewsSource(name, FetchResult.partial(articles));
	}

	@Override
	public String name() {
		return this.name;
	}

	@Override
	public FetchResult fetch(Instant since) {
		this.calls.add(since);
		return this.result;
	}

	public int callCount() {
		return this.calls.size();
	}

	/** 파이프라인이 넘긴 수집 창의 시작 시각. */
	public Instant lastSince() {
		return this.calls.isEmpty() ? null : this.calls.getLast();
	}
}
