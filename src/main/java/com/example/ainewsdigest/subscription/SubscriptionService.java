package com.example.ainewsdigest.subscription;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * {@code /start}·{@code /stop}으로 들어오는 구독 상태 전이 (ADR-003).
 *
 * <p><b>두 메서드 모두 멱등이다.</b> 같은 {@code chatId}로 {@code /start}가 여러 번 오는 것은 예외 상황이
 * 아니라 일상이다 — 사용자가 버튼을 두 번 누르거나, 폴러의 offset이 메모리에만 있어 재시작 시 텔레그램이
 * 최근 24시간 업데이트를 통째로 다시 보내주거나(ADR-008), 앱이 재시작 루프에 빠지면 그렇게 된다.
 * 여기서 행이 늘어나면 발송이 같은 사람에게 여러 번 나간다.
 *
 * <p>선택-후-삽입 사이의 경쟁 조건은 다루지 않는다. 이 서비스를 부르는 곳은 단일 스레드 폴러 하나뿐이고
 * ({@code TelegramUpdatePoller}), 마지막 방어선으로 {@code subscriber.chat_id} UNIQUE가 서 있다.
 */
@Service
public class SubscriptionService {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

	/** {@code subscriber.source}가 {@code varchar(50)}이다. */
	private static final int MAX_SOURCE_LENGTH = 50;

	private final SubscriberRepository subscribers;

	public SubscriptionService(SubscriberRepository subscribers) {
		this.subscribers = subscribers;
	}

	/**
	 * {@code /start} 처리. 신규면 등록, 기존 해지자면 재활성화, 이미 활성이면 아무것도 바꾸지 않는다.
	 *
	 * <p>반환값으로 셋을 구분하는 이유는 호출자가 <b>다르게</b> 응답해야 하기 때문이다. 활성 구독자에게
	 * 최근호를 다시 쏘면 같은 내용이 반복 발송되고, 재시작 루프에서는 그것이 증폭돼 레이트리밋에 걸린다.
	 *
	 * <p>재활성화 시 {@code source}는 갱신하지 않는다. 유입 경로는 <b>처음</b> 어디로 들어왔는가를 뜻하고,
	 * 재구독 시점의 payload로 덮으면 그 정보가 사라진다.
	 *
	 * @param payload 딥링크 start payload. <b>정제되지 않은 사용자 입력이다</b> ({@link #sanitizePayload})
	 */
	@Transactional
	public SubscribeOutcome start(long chatId, String payload) {
		Instant now = Instant.now();
		Optional<Subscriber> existing = this.subscribers.findByChatId(chatId);
		if (existing.isPresent()) {
			Subscriber subscriber = existing.get();
			if (subscriber.isActive()) {
				return SubscribeOutcome.ALREADY_ACTIVE;
			}
			subscriber.resubscribe(now);
			log.info("구독 재활성화 (chatId={})", chatId);
			return SubscribeOutcome.REACTIVATED;
		}
		String source = sanitizePayload(payload);
		this.subscribers.save(Subscriber.subscribe(chatId, source, now));
		log.info("신규 구독 (chatId={}, source={})", chatId, source);
		return SubscribeOutcome.NEW;
	}

	/** {@code /stop} 처리. 미등록이거나 이미 해지 상태면 아무 일도 하지 않는다. */
	@Transactional
	public void stop(long chatId) {
		this.subscribers.findByChatId(chatId)
				.filter(Subscriber::isActive)
				.ifPresent((subscriber) -> {
					subscriber.unsubscribe(Instant.now());
					log.info("구독 해지 (chatId={})", chatId);
				});
	}

	/**
	 * {@code /start} 뒤 문자열을 {@code subscriber.source}에 넣을 수 있는 형태로 정제한다.
	 *
	 * <p><b>딥링크 규격(1~64자, {@code [A-Za-z0-9_-]})을 신뢰하지 마라.</b> 그 규격은 텔레그램 클라이언트가
	 * <b>만드는 링크</b>에만 적용된다. 사용자가 대화창에 {@code /start } + 4,096자를 직접 치는 것은 아무것도
	 * 막지 않고 한글·이모지·따옴표도 얼마든지 들어온다. 정제 없이 저장하면 {@code varchar(50)} 제약 위반이
	 * 나는데, 그 예외는 {@code BotCommandHandler}가 삼키므로 <b>구독이 조용히 실패한다</b> — 사용자는
	 * 환영 메시지조차 받지 못하고 무엇이 잘못됐는지 알 방법이 없다.
	 *
	 * <p>허용 문자가 전부 ASCII라 절단이 서로게이트 페어를 쪼갤 일은 없다.
	 */
	private static String sanitizePayload(String raw) {
		if (raw == null) {
			return null;
		}
		StringBuilder cleaned = new StringBuilder();
		for (int index = 0; index < raw.length() && cleaned.length() < MAX_SOURCE_LENGTH; index++) {
			char character = raw.charAt(index);
			if (isAllowed(character)) {
				cleaned.append(character);
			}
		}
		return cleaned.isEmpty() ? null : cleaned.toString();
	}

	private static boolean isAllowed(char character) {
		return (character >= 'A' && character <= 'Z')
				|| (character >= 'a' && character <= 'z')
				|| (character >= '0' && character <= '9')
				|| character == '_' || character == '-';
	}

	/** {@code /start}의 결과. 호출자는 이 값으로 응답을 나눈다 — 최근호 재발송 여부가 여기서 갈린다. */
	public enum SubscribeOutcome {

		NEW,
		REACTIVATED,
		ALREADY_ACTIVE
	}
}
