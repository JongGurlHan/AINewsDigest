package com.example.ainewsdigest.delivery;

import com.example.ainewsdigest.digest.Digest;
import com.example.ainewsdigest.digest.DigestItem;
import com.example.ainewsdigest.digest.DigestQueryService;
import com.example.ainewsdigest.digest.DigestRepository;
import com.example.ainewsdigest.subscription.SubscriberRepository;
import com.example.ainewsdigest.subscription.SubscriberStatus;
import com.example.ainewsdigest.subscription.SubscriptionService;
import com.example.ainewsdigest.support.FakeMessenger;
import com.example.ainewsdigest.support.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 발송은 페이크 {@link Messenger}로 가로채고 DB만 실제 PostgreSQL이다. 최근호 선택 규칙
 * ({@code sentAt != null && status != EMPTY})은 쿼리로 표현돼 있어 실제 DB에서 확인해야 의미가 있다.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class BotCommandHandlerTest {

	private static final long CHAT_ID = 7_777L;

	private static final LocalDate TODAY = LocalDate.of(2026, 8, 6);

	private static final Instant NOW = Instant.parse("2026-08-06T07:30:00Z");

	@Autowired
	private SubscriptionService subscriptions;

	@Autowired
	private DigestQueryService digestQuery;

	@Autowired
	private DigestRepository digests;

	@Autowired
	private SubscriberRepository subscribers;

	@PersistenceContext
	private EntityManager entityManager;

	private final FakeMessenger messenger = new FakeMessenger();

	private BotCommandHandler handler;

	@BeforeEach
	void setUp() {
		this.handler = new BotCommandHandler(this.subscriptions, this.digestQuery, this.messenger);
	}

	@Test
	void startSendsWelcomeAndThenTheLatestDigest() {
		saveSentDigest(TODAY.minusDays(1), "<b>어제의 다이제스트</b>");

		this.handler.handle(update("/start web"));

		List<String> sent = this.messenger.htmlTo(CHAT_ID);
		assertEquals(2, sent.size(), () -> "발송 내역: " + sent);
		assertTrue(sent.get(0).contains("구독이 시작되었습니다"), sent.get(0));
		assertEquals("<b>어제의 다이제스트</b>", sent.get(1));
		assertEquals(SubscriberStatus.ACTIVE, this.subscribers.findByChatId(CHAT_ID).orElseThrow().getStatus());
	}

	/** 보낼 최근호가 없으면 환영 메시지 한 통뿐이다. 빈 안내를 굳이 두 번째 알림으로 울리지 않는다. */
	@Test
	void startSendsOnlyTheWelcomeWhenNothingHasEverBeenSent() {
		this.handler.handle(update("/start"));

		List<String> sent = this.messenger.htmlTo(CHAT_ID);
		assertEquals(1, sent.size(), () -> "발송 내역: " + sent);
		assertTrue(sent.getFirst().contains("아직 발송된 다이제스트가 없습니다"), sent.getFirst());
	}

	/** 아직 안 나간 다이제스트를 여기서 보내면 구독자가 오늘 아침에 또 받는다. */
	@Test
	void startNeverSendsAnUnsentDigest() {
		this.digests.save(Digest.pending(TODAY, "<b>오늘 아침에 나갈 것</b>", NOW));
		this.entityManager.flush();

		this.handler.handle(update("/start"));

		assertEquals(1, this.messenger.count());
		assertFalse(this.messenger.htmlTo(CHAT_ID).getFirst().contains("오늘 아침에 나갈 것"));
	}

	/** 환영 인사 직후 "오늘의 AI 뉴스는 없습니다"가 이어지면 첫인상이 망가진다. 한 칸 더 거슬러 올라간다. */
	@Test
	void startSkipsAnEmptyDigestAndSendsTheLastOneWithContent() {
		saveSentDigest(TODAY.minusDays(3), "<b>내용 있는 다이제스트</b>");
		saveSentEmptyDigest(TODAY.minusDays(1));

		this.handler.handle(update("/start"));

		List<String> sent = this.messenger.htmlTo(CHAT_ID);
		assertEquals(2, sent.size(), () -> "발송 내역: " + sent);
		assertEquals("<b>내용 있는 다이제스트</b>", sent.get(1));
	}

	/**
	 * 재시작 루프나 중복 입력으로 {@code /start}가 반복돼도 최근호는 한 번만 나가야 한다.
	 * 매번 쏘면 같은 사람에게 같은 내용이 반복 발송되고 레이트리밋에 걸린다.
	 */
	@Test
	void repeatedStartSendsTheLatestDigestOnlyOnce() {
		saveSentDigest(TODAY.minusDays(1), "<b>어제의 다이제스트</b>");

		this.handler.handle(update("/start"));
		this.handler.handle(update("/start"));
		this.handler.handle(update("/start"));

		List<String> sent = this.messenger.htmlTo(CHAT_ID);
		assertEquals(1, sent.stream().filter("<b>어제의 다이제스트</b>"::equals).count(), () -> "발송 내역: " + sent);
		assertEquals(4, sent.size(), () -> "발송 내역: " + sent);
		assertTrue(sent.get(2).contains("이미 구독 중입니다"), sent.get(2));
		assertTrue(sent.get(3).contains("이미 구독 중입니다"), sent.get(3));
	}

	/** 그룹 대화에서는 텔레그램 클라이언트가 봇 이름을 자동으로 붙인다. */
	@Test
	void recognizesCommandsWithABotSuffix() {
		this.handler.handle(update("/start@my_bot web"));

		assertEquals(SubscriberStatus.ACTIVE, this.subscribers.findByChatId(CHAT_ID).orElseThrow().getStatus());
		assertEquals("web", this.subscribers.findByChatId(CHAT_ID).orElseThrow().getSource());
		assertTrue(this.messenger.htmlTo(CHAT_ID).getFirst().contains("구독이 시작되었습니다"));
	}

	@Test
	void stopUnsubscribesAndConfirms() {
		this.handler.handle(update("/start"));

		this.handler.handle(update("/stop"));

		assertEquals(SubscriberStatus.UNSUBSCRIBED,
				this.subscribers.findByChatId(CHAT_ID).orElseThrow().getStatus());
		assertTrue(this.messenger.htmlTo(CHAT_ID).getLast().contains("구독이 해지되었습니다"));
	}

	@Test
	void unknownCommandGetsTheHelpText() {
		this.handler.handle(update("/왜안돼"));

		List<String> sent = this.messenger.htmlTo(CHAT_ID);
		assertEquals(1, sent.size());
		assertTrue(sent.getFirst().contains("/start - 구독 시작"), sent.getFirst());
	}

	@Test
	void helpGetsTheHelpText() {
		this.handler.handle(update("/help"));

		assertTrue(this.messenger.htmlTo(CHAT_ID).getFirst().contains("/start - 구독 시작"));
	}

	private static TelegramUpdate update(String text) {
		return new TelegramUpdate(1L, CHAT_ID, text);
	}

	private void saveSentDigest(LocalDate date, String messageText) {
		Digest digest = Digest.pending(date, messageText, NOW);
		digest.addItem(new DigestItem(1, "제목", "요약", "https://example.com/a", "https://example.com/a",
				"example.com", 5));
		digest.markSent(NOW);
		this.digests.save(digest);
		this.entityManager.flush();
	}

	private void saveSentEmptyDigest(LocalDate date) {
		Digest digest = Digest.empty(date, "오늘의 AI 뉴스는 없습니다.", NOW);
		digest.markSent(NOW);
		this.digests.save(digest);
		this.entityManager.flush();
	}
}
