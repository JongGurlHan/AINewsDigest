package com.example.ainewsdigest.global;

import com.example.ainewsdigest.delivery.Messenger;
import com.example.ainewsdigest.delivery.SendResult;
import com.example.ainewsdigest.delivery.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 배치 장애를 관리자 텔레그램 방으로 알린다 (ADR-010의 앞쪽 절반. 뒤쪽은 {@link HealthPinger}다).
 *
 * <h2>여기서 예외가 나가면 안 된다</h2>
 * 이 클래스가 불리는 시점은 이미 무언가 잘못된 뒤다. 알림이 실패해 예외를 던지면 그 예외가 스케줄러의
 * 다음 단계를 날리고, <b>원래 알리려던 장애는 아무 데도 기록되지 않는다.</b> 감시 장치의 실패는 감시
 * 장치 안에서 끝나야 한다. 그래서 {@link Messenger}가 예외를 던지지 않기로 계약했음에도(ADR-016)
 * 한 번 더 감싼다 — 계약이 깨졌을 때 조용히 무너지는 쪽이 훨씬 나쁘다.
 *
 * <p>같은 이유로 {@code admin-chat-id}가 비어 있거나 숫자가 아니어도 로그만 남기고 넘어간다.
 * 알림 설정이 없는 배포에서 배치가 멈출 이유가 없다.
 *
 * <h2>발송 채널은 {@link Messenger} 하나뿐이다</h2>
 * 관리자 알림용 클라이언트를 따로 만들지 않는다. 구독자에게 보내는 경로와 같은 코드를 타야
 * "구독자에게는 나가는데 관리자에게만 안 나가는" 상태가 생기지 않는다.
 */
@Component
public class AdminNotifier {

	private static final Logger log = LoggerFactory.getLogger(AdminNotifier.class);

	/**
	 * 조립된 HTML의 상한. 텔레그램 한도는 4,096자(after entities parsing)이고 엔티티는 파싱 후 줄어들므로
	 * 원문 기준 이 값이면 안전하다. 예외 메시지에는 스택트레이스가 통째로 실려 오는 일이 흔한데,
	 * 그 한 건 때문에 알림이 400으로 사라지면 장애가 있는 날에만 터지는 버그가 된다.
	 */
	private static final int MAX_HTML_LENGTH = 3_500;

	/** 제목이 본문을 밀어내지 않도록 따로 상한을 둔다. */
	private static final int MAX_TITLE_LENGTH = 300;

	private static final String ELLIPSIS = "…";

	private final Messenger messenger;

	private final TelegramProperties telegram;

	public AdminNotifier(Messenger messenger, TelegramProperties telegram) {
		this.messenger = messenger;
		this.telegram = telegram;
	}

	/** 사람이 개입해야 하는 상황. 그날 다이제스트가 나가지 못했거나, 나갔어도 아무도 받지 못했다. */
	public void notifyFailure(String title, String detail) {
		notify("실패", title, detail);
	}

	/** 배치는 굴러갔지만 이상이 섞인 상황. 복구 보고도 여기로 온다 — 앞선 실패 알림을 무효화하는 소식이다. */
	public void notifyWarning(String title, String detail) {
		notify("경고", title, detail);
	}

	private void notify(String level, String title, String detail) {
		Long chatId = adminChatId();
		if (chatId == null) {
			log.info("관리자 알림 대상이 없어 보내지 않는다 [{}] {}: {}", level, title, detail);
			return;
		}
		String html = compose(level, title, detail);
		try {
			SendResult result = this.messenger.send(chatId, html);
			if (!(result instanceof SendResult.Success)) {
				log.warn("관리자 알림이 전송되지 않았다 [{}] {}: {}", level, title, result);
			}
		}
		catch (RuntimeException ex) {
			// 여기서 다시 던지면 알림 실패가 2차 장애가 된다.
			log.warn("관리자 알림 전송 중 예외 [{}] {}: {}", level, title, ex.toString());
		}
	}

	/**
	 * {@code admin-chat-id}는 문자열 설정이지만 텔레그램의 {@code chat_id}는 숫자다. 파싱 실패를 예외로
	 * 올려보내지 않는 이유는 위 클래스 주석과 같다 — 오타 하나로 배치가 멈추는 것이 알림이 안 가는 것보다 나쁘다.
	 */
	private Long adminChatId() {
		String raw = this.telegram.adminChatId();
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(raw.trim());
		}
		catch (NumberFormatException ex) {
			log.warn("admin-chat-id가 숫자가 아니라 관리자 알림을 보낼 수 없다: {}", raw);
			return null;
		}
	}

	private static String compose(String level, String title, String detail) {
		StringBuilder html = new StringBuilder("<b>[").append(level).append("] ");
		appendEscaped(html, title, MAX_TITLE_LENGTH);
		html.append("</b>\n");
		appendEscaped(html, detail, MAX_HTML_LENGTH);
		return html.toString();
	}

	/**
	 * 이스케이프와 절단을 <b>한 번에</b> 한다.
	 *
	 * <p>순서를 나누면 둘 중 하나가 반드시 깨진다. 자르고 이스케이프하면 {@code &}가 5배로 늘어 상한을
	 * 넘고, 이스케이프하고 자르면 {@code &amp;}가 {@code &am}으로 쪼개져 텔레그램이 400을 돌려준다.
	 * 여기서는 치환 결과를 통째로 넣을 수 있을 때만 넣으므로 엔티티도 서로게이트 페어도 반으로 갈라지지 않는다.
	 *
	 * <p>{@code budget}은 {@code out} 전체 길이의 상한이다. 텔레그램 HTML 모드의 특수문자는
	 * {@code & < >} 셋뿐이다 (ADR-009).
	 *
	 * <p>{@code DigestMessageBuilder}에도 비슷한 코드가 있지만 그쪽은 "보이는 길이"를 함께 세는 조립기의
	 * 내부 구현이다. 공유하려고 끌어내면 발송 본문의 길이 계산 규칙이 알림 코드에 묶인다.
	 */
	private static void appendEscaped(StringBuilder out, String text, int budget) {
		if (text == null) {
			return;
		}
		int index = 0;
		while (index < text.length()) {
			int codePoint = text.codePointAt(index);
			String piece = switch (codePoint) {
				case '&' -> "&amp;";
				case '<' -> "&lt;";
				case '>' -> "&gt;";
				default -> new String(Character.toChars(codePoint));
			};
			if (out.length() + piece.length() > budget) {
				out.append(ELLIPSIS);
				return;
			}
			out.append(piece);
			index += Character.charCount(codePoint);
		}
	}
}
