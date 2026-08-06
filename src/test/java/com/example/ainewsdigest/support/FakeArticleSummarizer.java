package com.example.ainewsdigest.support;

import com.example.ainewsdigest.curation.ArticleSummarizer;
import com.example.ainewsdigest.curation.ArticleWithContent;
import com.example.ainewsdigest.curation.SummarizedArticle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * {@link ArticleSummarizer} 인메모리 페이크. 한글 제목·요약 생성 규칙을 테스트가 함수로 주입한다.
 *
 * <p>기본 규칙은 상한 안쪽의 짧은 문자열이다. 컬럼 폭 절단을 검증할 때만
 * {@link #withTitle(Function)}으로 상한을 넘는 값을 돌려주게 만든다.
 */
public class FakeArticleSummarizer implements ArticleSummarizer {

	private final List<List<ArticleWithContent>> calls = new ArrayList<>();

	private Function<ArticleWithContent, String> titleFactory =
			(article) -> "한글 제목: " + article.article().title();

	private Function<ArticleWithContent, String> summaryFactory =
			(article) -> "한글 요약: " + article.article().title();

	public FakeArticleSummarizer withTitle(Function<ArticleWithContent, String> titleFactory) {
		this.titleFactory = titleFactory;
		return this;
	}

	public FakeArticleSummarizer withSummary(Function<ArticleWithContent, String> summaryFactory) {
		this.summaryFactory = summaryFactory;
		return this;
	}

	@Override
	public List<SummarizedArticle> summarize(List<ArticleWithContent> articles) {
		this.calls.add(List.copyOf(articles));
		return articles.stream()
				.map((article) -> new SummarizedArticle(article.article(), article.score(),
						this.titleFactory.apply(article), this.summaryFactory.apply(article)))
				.toList();
	}

	public int callCount() {
		return this.calls.size();
	}

	public List<ArticleWithContent> lastArticles() {
		return this.calls.isEmpty() ? List.of() : this.calls.getLast();
	}
}
