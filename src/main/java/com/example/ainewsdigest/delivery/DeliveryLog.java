package com.example.ainewsdigest.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 구독자 한 명에 대한 발송 시도 기록. 재개 시 SUCCESS가 있는 구독자를 건너뛰는 근거가 된다 (ADR-014).
 *
 * <p>다른 애그리거트를 {@code @ManyToOne}으로 참조하지 않고 ID만 들고 있다. 발송은 구독자 단위로
 * 트랜잭션을 분리하므로 엔티티 참조는 불필요한 로딩과 영속성 컨텍스트 결합만 만든다.
 */
@Entity
@Table(name = "delivery_log")
public class DeliveryLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "digest_id", nullable = false)
	private Long digestId;

	@Column(name = "subscriber_id", nullable = false)
	private Long subscriberId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private DeliveryStatus status;

	/** 텔레그램 오류 코드 (403, 429 등). 성공이면 null이다. */
	@Column(name = "error_code", length = 50)
	private String errorCode;

	@Column(name = "attempted_at", nullable = false)
	private Instant attemptedAt;

	protected DeliveryLog() {
	}

	private DeliveryLog(Long digestId, Long subscriberId, DeliveryStatus status,
			String errorCode, Instant attemptedAt) {
		this.digestId = digestId;
		this.subscriberId = subscriberId;
		this.status = status;
		this.errorCode = errorCode;
		this.attemptedAt = attemptedAt;
	}

	public static DeliveryLog success(Long digestId, Long subscriberId, Instant attemptedAt) {
		return new DeliveryLog(digestId, subscriberId, DeliveryStatus.SUCCESS, null, attemptedAt);
	}

	public static DeliveryLog failure(Long digestId, Long subscriberId, String errorCode, Instant attemptedAt) {
		return new DeliveryLog(digestId, subscriberId, DeliveryStatus.FAILED, errorCode, attemptedAt);
	}

	public Long getId() {
		return this.id;
	}

	public Long getDigestId() {
		return this.digestId;
	}

	public Long getSubscriberId() {
		return this.subscriberId;
	}

	public DeliveryStatus getStatus() {
		return this.status;
	}

	public String getErrorCode() {
		return this.errorCode;
	}

	public Instant getAttemptedAt() {
		return this.attemptedAt;
	}
}
