package com.example.ainewsdigest.digest;

import com.example.ainewsdigest.support.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 화면용 조회 규칙을 실제 PostgreSQL로 검증한다 (ADR-011). 여기서 지켜야 하는 것은 둘이다 —
 * <b>노출 기준이 {@code status}가 아니라 {@code sentAt}</b>이라는 것(ADR-014), 그리고 목록이
 * 항목까지 한 번에 읽는다는 것.
 *
 * <p>{@code generate_statistics}를 이 클래스에서만 켠다. 전역 테스트 설정에 두면 모든 컨텍스트가
 * 세션 메트릭을 로깅해 출력이 시끄러워진다.
 */
@SpringBootTest(properties = {
		"spring.jpa.properties.hibernate.generate_statistics=true",
		"logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=warn"
})
@Import(TestcontainersConfig.class)
@Transactional
class DigestQueryServiceTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 8, 6);

	private static final Instant GENERATED_AT = Instant.parse("2026-08-05T22:00:00Z");

	private static final Instant SENT_AT = Instant.parse("2026-08-05T22:30:00Z");

	@Autowired
	private DigestQueryService service;

	@Autowired
	private DigestRepository digests;

	@PersistenceContext
	private EntityManager entityManager;

	private static DigestItem item(int position, String titleKo) {
		return new DigestItem(position, titleKo, "한글 요약", "https://example.com/" + position,
				"example.com/" + position, "example.com", 4);
	}

	private Digest sent(LocalDate date, String... titles) {
		Digest digest = Digest.pending(date, "<b>본문</b>", GENERATED_AT);
		for (int index = 0; index < titles.length; index++) {
			digest.addItem(item(index + 1, titles[index]));
		}
		digest.markSent(SENT_AT);
		return this.digests.save(digest);
	}

	private Digest unsent(LocalDate date, String title) {
		Digest digest = Digest.pending(date, "<b>본문</b>", GENERATED_AT);
		digest.addItem(item(1, title));
		return this.digests.save(digest);
	}

	private void detach() {
		this.entityManager.flush();
		this.entityManager.clear();
	}

	@Test
	void listsOnlySentDigests() {
		sent(TODAY.minusDays(1), "어제 기사");
		unsent(TODAY, "오늘 기사");
		detach();

		Page<DigestView> page = this.service.findSentPage(PageRequest.of(0, 20));

		assertEquals(1, page.getTotalElements());
		assertEquals(TODAY.minusDays(1), page.getContent().getFirst().digestDate());
		assertEquals(List.of("어제 기사"),
				page.getContent().getFirst().items().stream().map(DigestItemView::titleKo).toList());
	}

	@Test
	void landingAndDetailAlsoHideUnsentDigests() {
		unsent(TODAY, "오늘 기사");
		detach();

		assertTrue(this.service.findRecentSent(5).isEmpty());
		assertTrue(this.service.findSentByDate(TODAY).isEmpty());
	}

	/**
	 * 발송된 EMPTY는 목록에 남아야 한다. {@code status = SENT}로 거르면 뉴스가 없던 날이 아카이브에서
	 * 통째로 사라지고, ADR-013의 점수 임계값을 튜닝할 근거도 함께 없어진다.
	 */
	@Test
	void sentEmptyDigestStaysInTheListAndIsMarkedEmpty() {
		Digest empty = Digest.empty(TODAY, "오늘의 AI 뉴스는 없습니다.", GENERATED_AT);
		empty.markSent(SENT_AT);
		this.digests.save(empty);
		sent(TODAY.minusDays(1), "어제 기사");
		detach();

		Page<DigestView> page = this.service.findSentPage(PageRequest.of(0, 20));

		assertEquals(2, page.getTotalElements());
		DigestView first = page.getContent().getFirst();
		assertEquals(TODAY, first.digestDate());
		assertTrue(first.isEmpty());
		assertTrue(first.items().isEmpty());
		assertFalse(page.getContent().get(1).isEmpty());

		assertTrue(this.service.findSentByDate(TODAY).orElseThrow().isEmpty());
	}

	@Test
	void newestComesFirst() {
		sent(TODAY.minusDays(2), "그저께");
		sent(TODAY, "오늘");
		sent(TODAY.minusDays(1), "어제");
		detach();

		assertEquals(List.of(TODAY, TODAY.minusDays(1), TODAY.minusDays(2)),
				this.service.findRecentSent(5).stream().map(DigestView::digestDate).toList());
	}

	@Test
	void findRecentSentHonoursTheLimit() {
		sent(TODAY.minusDays(2), "그저께");
		sent(TODAY.minusDays(1), "어제");
		sent(TODAY, "오늘");
		detach();

		assertEquals(2, this.service.findRecentSent(2).size());
	}

	@Test
	void detailReturnsItemsOfThatDateOnly() {
		sent(TODAY, "첫째", "둘째", "셋째");
		sent(TODAY.minusDays(1), "어제 기사");
		detach();

		DigestView digest = this.service.findSentByDate(TODAY).orElseThrow();

		assertEquals("2026년 8월 6일 (목)", digest.displayDate());
		assertEquals(List.of("첫째", "둘째", "셋째"),
				digest.items().stream().map(DigestItemView::titleKo).toList());
	}

	/**
	 * 목록은 다이제스트 건수와 무관하게 쿼리 3회(count + id 페이지 + 항목 fetch)로 끝난다.
	 * N+1이면 다이제스트마다 항목 조회가 한 번씩 더 붙어 6회가 된다.
	 */
	@Test
	void listLoadsItemsWithoutNPlusOneQueries() {
		sent(TODAY, "가", "나", "다");
		sent(TODAY.minusDays(1), "라", "마", "바");
		sent(TODAY.minusDays(2), "사", "아", "자");
		detach();

		Statistics statistics = this.entityManager.getEntityManagerFactory()
				.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();

		Page<DigestView> page = this.service.findSentPage(PageRequest.of(0, 20));
		// 항목까지 실제로 채워졌는지 확인한다 — 쿼리 수만 보면 "덜 읽어서 적은" 경우와 구분되지 않는다.
		assertEquals(9, page.getContent().stream().mapToInt((digest) -> digest.items().size()).sum());

		long queries = statistics.getPrepareStatementCount();
		// 하한도 본다. 통계가 꺼져 있으면 0이 돌아와 상한 검사만으로는 아무것도 검증하지 못한다.
		assertTrue(queries >= 2, "쿼리 " + queries + "회 — 통계가 켜져 있지 않다");
		assertTrue(queries <= 3, "쿼리 " + queries + "회 — 항목을 건건이 조회하고 있다");
	}
}
