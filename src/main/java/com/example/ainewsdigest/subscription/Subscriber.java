package com.example.ainewsdigest.subscription;

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
 * 텔레그램 구독자. {@code chat_id}는 {@code /start} 시점에만 얻을 수 있고 발송의 유일한 주소다 (ADR-003).
 *
 * <p>스키마의 {@code consecutive_failures}는 일부러 매핑하지 않는다. MVP 검수에서 자동 해지 카운터를
 * 제거했고, 죽은 구독자는 발송 시 403을 받는 즉시 해지된다. not null default 0이라 INSERT에 무해하다.
 */
@Entity
@Table(name = "subscriber")
public class Subscriber {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "chat_id", nullable = false, unique = true)
	private long chatId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private SubscriberStatus status;

	/** 텔레그램 deep link start payload (유입 경로). payload 없이 {@code /start}만 오면 null이다. */
	@Column(name = "source", length = 50)
	private String source;

	@Column(name = "subscribed_at", nullable = false)
	private Instant subscribedAt;

	@Column(name = "unsubscribed_at")
	private Instant unsubscribedAt;

	protected Subscriber() {
	}

	private Subscriber(long chatId, String source, Instant now) {
		this.chatId = chatId;
		this.source = source;
		this.status = SubscriberStatus.ACTIVE;
		this.subscribedAt = now;
	}

	public static Subscriber subscribe(long chatId, String source, Instant now) {
		return new Subscriber(chatId, source, now);
	}

	/** 해지했던 구독자가 다시 {@code /start}를 보낸 경우. 행을 새로 만들지 않고 되살린다. */
	public void resubscribe(Instant now) {
		this.status = SubscriberStatus.ACTIVE;
		this.subscribedAt = now;
		this.unsubscribedAt = null;
	}

	public void unsubscribe(Instant now) {
		this.status = SubscriberStatus.UNSUBSCRIBED;
		this.unsubscribedAt = now;
	}

	public boolean isActive() {
		return this.status == SubscriberStatus.ACTIVE;
	}

	public Long getId() {
		return this.id;
	}

	public long getChatId() {
		return this.chatId;
	}

	public SubscriberStatus getStatus() {
		return this.status;
	}

	public String getSource() {
		return this.source;
	}

	public Instant getSubscribedAt() {
		return this.subscribedAt;
	}

	public Instant getUnsubscribedAt() {
		return this.unsubscribedAt;
	}
}
