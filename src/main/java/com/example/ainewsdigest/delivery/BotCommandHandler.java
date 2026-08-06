package com.example.ainewsdigest.delivery;

import com.example.ainewsdigest.digest.DigestQueryService;
import com.example.ainewsdigest.digest.DigestView;
import com.example.ainewsdigest.subscription.SubscriptionService;
import com.example.ainewsdigest.subscription.SubscriptionService.SubscribeOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * 봇 명령 한 건을 해석해 처리한다. {@code /start}·{@code /stop}·{@code /help} 셋이 전부다
 * (개인화 명령은 PRD의 MVP 제외 사항이다).
 *
 * <p><b>{@code handle}은 예외를 던지지 않는다.</b> 여기서 예외가 새면 폴링 루프가 죽고 구독 접수가
 * 통째로 멈춘다. 다만 삼킨다는 것은 곧 사용자가 아무 응답도 받지 못한다는 뜻이므로, 삼켜도 되는 상황을
 * 애초에 만들지 않는 것이 먼저다 — {@code /start} payload 정제가 그래서 서비스 안에 있다.
 *
 * <p>DB 접근은 {@link SubscriptionService}·{@link DigestQueryService}의 트랜잭션 안에서 끝나고,
 * 텔레그램 발송은 그 <b>바깥</b>에서 일어난다. 이 클래스에 {@code @Transactional}을 붙이면 외부 HTTP
 * 호출이 트랜잭션 안으로 들어가 커넥션을 수 초간 점유한다 (CLAUDE.md CRITICAL).
 */
@Component
public class BotCommandHandler {

	private static final Logger log = LoggerFactory.getLogger(BotCommandHandler.class);

	private static final String COMMAND_START = "/start";

	private static final String COMMAND_STOP = "/stop";

	private static final String WELCOME = """
			<b>AI News Digest</b> 구독이 시작되었습니다.
			개발자에게 유용한 AI 소식 3~5건을 매일 오전 7시 30분에 한글 요약으로 보내드립니다.

			/stop - 구독 해지
			/help - 안내 보기""";

	/**
	 * 보낼 최근호가 없을 때만 환영 메시지 뒤에 붙는다. <b>별도의 두 번째 메시지로 보내지 않는다</b> —
	 * 아무 내용도 없는 안내가 알림을 한 번 더 울릴 이유가 없다.
	 */
	private static final String NO_DIGEST_YET = """


			아직 발송된 다이제스트가 없습니다. 내일 아침 첫 소식을 보내드릴게요.""";

	private static final String ALREADY_ACTIVE = """
			이미 구독 중입니다. 매일 오전 7시 30분에 보내드립니다.

			/stop - 구독 해지
			/help - 안내 보기""";

	private static final String UNSUBSCRIBED = """
			구독이 해지되었습니다. 그동안 읽어주셔서 감사합니다.
			다시 받아보고 싶으면 언제든 /start 를 보내주세요.""";

	private static final String HELP = """
			<b>AI News Digest</b>
			개발자에게 유용한 AI 소식 3~5건을 매일 오전 7시 30분에 한글 요약으로 보내드립니다.

			/start - 구독 시작
			/stop - 구독 해지
			/help - 이 안내 보기""";

	private final SubscriptionService subscriptions;

	private final DigestQueryService digests;

	private final Messenger messenger;

	public BotCommandHandler(SubscriptionService subscriptions, DigestQueryService digests, Messenger messenger) {
		this.subscriptions = subscriptions;
		this.digests = digests;
		this.messenger = messenger;
	}

	/** 업데이트 하나를 처리한다. 예외를 던지지 않는다. */
	public void handle(TelegramUpdate update) {
		try {
			dispatch(update);
		}
		catch (RuntimeException ex) {
			// 봇 토큰은 여기 오는 예외에 섞이지 않는다 (텔레그램 호출은 SendResult로 돌아온다).
			log.warn("업데이트 처리 실패 (updateId={}, chatId={}): {}",
					update.updateId(), update.chatId(), ex.toString());
		}
	}

	private void dispatch(TelegramUpdate update) {
		String text = (update.text() == null) ? "" : update.text().strip();
		int separator = indexOfFirstSpace(text);
		String command = normalize((separator < 0) ? text : text.substring(0, separator));
		String payload = (separator < 0) ? null : text.substring(separator + 1).strip();
		switch (command) {
			case COMMAND_START -> start(update.chatId(), payload);
			case COMMAND_STOP -> stop(update.chatId());
			// /help도 알 수 없는 명령도 같은 안내다. 사용자가 원하는 것은 "무엇을 칠 수 있는가" 하나뿐이다.
			default -> send(update.chatId(), HELP);
		}
	}

	/**
	 * <b>{@link SubscribeOutcome}으로 분기한다.</b> 최근호는 실제로 구독 상태가 바뀐 경우에만 보낸다.
	 * 이미 활성인 사람에게 매번 다시 쏘면 같은 내용이 반복 발송되고, 앱이 재시작 루프에 빠져 텔레그램이
	 * 미확인 {@code /start}를 계속 재전송하는 상황에서는 그것이 증폭돼 레이트리밋에 걸린다.
	 */
	private void start(long chatId, String payload) {
		if (this.subscriptions.start(chatId, payload) == SubscribeOutcome.ALREADY_ACTIVE) {
			send(chatId, ALREADY_ACTIVE);
			return;
		}
		Optional<DigestView> latest = this.digests.findLatestSentWithContent();
		send(chatId, latest.isPresent() ? WELCOME : WELCOME + NO_DIGEST_YET);
		// 조립된 본문을 그대로 재사용한다. 여기서 다시 만들면 실제 발송분과 문구가 어긋난다.
		latest.ifPresent((digest) -> send(chatId, digest.messageText()));
	}

	private void stop(long chatId) {
		this.subscriptions.stop(chatId);
		send(chatId, UNSUBSCRIBED);
	}

	/**
	 * {@code /start@my_bot}처럼 봇 이름이 붙은 형태도 같은 명령이다 — 그룹 대화에서 텔레그램 클라이언트가
	 * 자동으로 붙인다. 대소문자도 맞춰준다.
	 */
	private static String normalize(String token) {
		int at = token.indexOf('@');
		String command = (at < 0) ? token : token.substring(0, at);
		return command.toLowerCase(Locale.ROOT);
	}

	private static int indexOfFirstSpace(String text) {
		for (int index = 0; index < text.length(); index++) {
			if (Character.isWhitespace(text.charAt(index))) {
				return index;
			}
		}
		return -1;
	}

	/**
	 * 발송 실패는 로그로 끝낸다. 명령 응답 한 통이 실패했다고 폴링을 멈출 이유가 없고, 차단(403) 처리는
	 * 다이제스트 발송(step 9)에서 다룬다 — 봇을 차단한 사람은 애초에 {@code /start}를 보내지 않는다.
	 */
	private void send(long chatId, String html) {
		SendResult result = this.messenger.send(chatId, html);
		if (!(result instanceof SendResult.Success)) {
			log.warn("봇 응답 발송 실패 (chatId={}): {}", chatId, result);
		}
	}
}
