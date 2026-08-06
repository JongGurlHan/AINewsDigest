package com.example.ainewsdigest.support;

import com.example.ainewsdigest.delivery.Messenger;
import com.example.ainewsdigest.delivery.SendResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 인메모리 {@link Messenger}. 발송은 인터페이스 뒤에 있으므로 테스트가 실제 텔레그램을 때릴 이유가 없다.
 *
 * <p>기본은 전부 성공이다. 실패 분기가 필요한 테스트만 아래로 바꾼다 — 우선순위는
 * {@link #script(long, SendResult...)}(순서대로 소진) &gt; {@link #always(long, SendResult)}(그 방만 고정) &gt;
 * {@link #returning(SendResult)}(전체 기본값) 순이다.
 */
public class FakeMessenger implements Messenger {

	private final List<Sent> sent = new ArrayList<>();

	private final Map<Long, Deque<SendResult>> scripts = new HashMap<>();

	private final Map<Long, SendResult> sticky = new HashMap<>();

	private SendResult result;

	private Consumer<Sent> observer;

	@Override
	public SendResult send(long chatId, String html) {
		Sent message = new Sent(chatId, html);
		this.sent.add(message);
		if (this.observer != null) {
			this.observer.accept(message);
		}
		Deque<SendResult> script = this.scripts.get(chatId);
		if (script != null && !script.isEmpty()) {
			return script.poll();
		}
		SendResult fixed = this.sticky.get(chatId);
		if (fixed != null) {
			return fixed;
		}
		return (this.result != null) ? this.result : new SendResult.Success(this.sent.size());
	}

	public FakeMessenger returning(SendResult result) {
		this.result = result;
		return this;
	}

	/** 이 방에 대해서만 대본대로 돌려준다. 대본이 소진되면 고정값·기본값으로 넘어간다. */
	public FakeMessenger script(long chatId, SendResult... results) {
		this.scripts.put(chatId, new ArrayDeque<>(List.of(results)));
		return this;
	}

	/** 이 방은 몇 번을 보내든 같은 결과다. 재시도 상한을 검증할 때 쓴다. */
	public FakeMessenger always(long chatId, SendResult result) {
		this.sticky.put(chatId, result);
		return this;
	}

	/**
	 * 발송 <b>도중에</b> 끼어들 지점. 앞선 구독자의 기록이 이미 커밋됐는지처럼, 순회가 끝난 뒤에는
	 * 확인할 수 없는 것을 검증하는 데 쓴다.
	 */
	public FakeMessenger observing(Consumer<Sent> observer) {
		this.observer = observer;
		return this;
	}

	public List<Sent> sent() {
		return List.copyOf(this.sent);
	}

	public List<String> htmlTo(long chatId) {
		return this.sent.stream().filter((message) -> message.chatId() == chatId).map(Sent::html).toList();
	}

	public int countTo(long chatId) {
		return htmlTo(chatId).size();
	}

	public int count() {
		return this.sent.size();
	}

	public record Sent(long chatId, String html) {
	}
}
