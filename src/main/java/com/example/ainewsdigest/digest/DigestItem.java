package com.example.ainewsdigest.digest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 다이제스트에 실린 기사 한 건. 순서는 {@code position}이 정한다 ({@code Digest.items}의 {@code @OrderBy}). */
@Entity
@Table(name = "digest_item")
public class DigestItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "digest_id", nullable = false)
	private Digest digest;

	@Column(name = "position", nullable = false)
	private int position;

	@Column(name = "title_ko", nullable = false, length = 200)
	private String titleKo;

	@Column(name = "summary_ko", nullable = false)
	private String summaryKo;

	@Column(name = "source_url", nullable = false)
	private String sourceUrl;

	/** 중복 발송 방지용 정규화 URL. 최근 7일치와 대조한다. */
	@Column(name = "normalized_url", nullable = false)
	private String normalizedUrl;

	@Column(name = "source_domain", nullable = false, length = 100)
	private String sourceDomain;

	/** LLM 선별 점수 1~5. 3점 이상만 채택된다 (ADR-013). */
	@Column(name = "score", nullable = false)
	private int score;

	protected DigestItem() {
	}

	public DigestItem(int position, String titleKo, String summaryKo, String sourceUrl,
			String normalizedUrl, String sourceDomain, int score) {
		this.position = position;
		this.titleKo = titleKo;
		this.summaryKo = summaryKo;
		this.sourceUrl = sourceUrl;
		this.normalizedUrl = normalizedUrl;
		this.sourceDomain = sourceDomain;
		this.score = score;
	}

	/** 양방향 연관관계는 {@link Digest#addItem(DigestItem)}에서만 맞춘다. */
	void assignTo(Digest digest) {
		this.digest = digest;
	}

	public Long getId() {
		return this.id;
	}

	public Digest getDigest() {
		return this.digest;
	}

	public int getPosition() {
		return this.position;
	}

	public String getTitleKo() {
		return this.titleKo;
	}

	public String getSummaryKo() {
		return this.summaryKo;
	}

	public String getSourceUrl() {
		return this.sourceUrl;
	}

	public String getNormalizedUrl() {
		return this.normalizedUrl;
	}

	public String getSourceDomain() {
		return this.sourceDomain;
	}

	public int getScore() {
		return this.score;
	}
}
