package com.example.ainewsdigest.support;

import com.example.ainewsdigest.delivery.Messenger;
import com.example.ainewsdigest.delivery.SendResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 인메모리 {@link Messenger}. 발송은 인터페이스 뒤에 있으므로 테스트가 실제 텔레그램을 때릴 이유가 없다.
 *
 * <p>기본은 전부 성공이다. 실패 분기가 필요한 테스트만 {@link #returning(SendResult)}로 바꾼다.
 */
public class FakeMessenger implements Messenger {

	private final List<Sent> sent = new ArrayList<>();

	private SendResult result;

	@Override
	public SendResult send(long chatId, String html) {
		this.sent.add(new Sent(chatId, html));
		return (this.result != null) ? this.result : new SendResult.Success(this.sent.size());
	}

	public FakeMessenger returning(SendResult result) {
		this.result = result;
		return this;
	}

	public List<Sent> sent() {
		return List.copyOf(this.sent);
	}

	public List<String> htmlTo(long chatId) {
		return this.sent.stream().filter((message) -> message.chatId() == chatId).map(Sent::html).toList();
	}

	public int count() {
		return this.sent.size();
	}

	public record Sent(long chatId, String html) {
	}
}
