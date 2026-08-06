package com.example.ainewsdigest.digest;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 하루치 다이제스트. {@code digest_date} UNIQUE가 하루 1회 생성의 멱등성을 보장한다.
 *
 * <p><b>두 축을 섞지 마라.</b> {@code status}는 콘텐츠의 성격(항목이 있는가 / 뉴스가 없었는가),
 * {@code sentAt}은 발송 여부다. EMPTY를 SENT로 덮는 순간 "그날 뉴스가 없었다"는 정보가 사라져
 * ADR-013의 점수 임계값을 튜닝할 근거가 없어진다 (ADR-014).
 */
@Entity
@Table(name = "digest")
public class Digest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "digest_date", nullable = false, unique = true)
	private LocalDate digestDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private DigestStatus status;

	/** 텔레그램으로 나가는 조립된 HTML 메시지 본문. */
	@Column(name = "message_text")
	private String messageText;

	@Column(name = "generated_at")
	private Instant generatedAt;

	@Column(name = "sent_at")
	private Instant sentAt;

	/**
	 * {@code @OrderBy}가 없으면 DB가 돌려주는 순서 그대로다. 순서 보장이 없어 아카이브 화면의
	 * 1·2·3번이 뒤섞일 수 있고, 재현되지 않아 찾기 어려운 버그가 된다.
	 */
	@OneToMany(mappedBy = "digest", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("position ASC")
	private List<DigestItem> items = new ArrayList<>();

	protected Digest() {
	}

	private Digest(LocalDate digestDate, DigestStatus status, String messageText, Instant now) {
		this.digestDate = digestDate;
		this.status = status;
		this.messageText = messageText;
		this.generatedAt = now;
	}

	public static Digest pending(LocalDate date, String messageText, Instant now) {
		return new Digest(date, DigestStatus.PENDING, messageText, now);
	}

	/** 채택 기준(3점)을 넘은 기사가 0건인 날. 침묵하지 않으므로 EMPTY도 발송 대상이다 (ADR-013). */
	public static Digest empty(LocalDate date, String messageText, Instant now) {
		return new Digest(date, DigestStatus.EMPTY, messageText, now);
	}

	public void addItem(DigestItem item) {
		this.items.add(item);
		item.assignTo(this);
	}

	/**
	 * 호출 여부는 발송 결과를 아는 쪽이 판단한다. 전원 실패면 아예 호출하지 않아 {@code sentAt}을
	 * 비워 둔다 — 그래야 재실행 여지가 남는다 (ADR-018). 여기서 조건을 따지지 않는다.
	 */
	public void markSent(Instant now) {
		if (this.status == DigestStatus.PENDING) {
			this.status = DigestStatus.SENT;
		}
		this.sentAt = now;
	}

	public boolean isSent() {
		return this.sentAt != null;
	}

	public Long getId() {
		return this.id;
	}

	public LocalDate getDigestDate() {
		return this.digestDate;
	}

	public DigestStatus getStatus() {
		return this.status;
	}

	public String getMessageText() {
		return this.messageText;
	}

	public Instant getGeneratedAt() {
		return this.generatedAt;
	}

	public Instant getSentAt() {
		return this.sentAt;
	}

	public List<DigestItem> getItems() {
		return Collections.unmodifiableList(this.items);
	}
}
