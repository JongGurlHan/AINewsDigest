package com.example.ainewsdigest.digest;

import com.example.ainewsdigest.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import(TestcontainersConfig.class)
class DigestRepositoryTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 8, 6);
	private static final Instant GENERATED_AT = Instant.parse("2026-08-05T22:00:00Z");
	private static final Instant SENT_AT = Instant.parse("2026-08-05T22:30:00Z");

	@Autowired
	private DigestRepository digests;

	@Autowired
	private TestEntityManager entityManager;

	private static DigestItem item(int position, String titleKo, String normalizedUrl) {
		return new DigestItem(position, titleKo, "한글 요약 본문", "https://" + normalizedUrl,
				normalizedUrl, "example.com", 4);
	}

	/** 역순으로 담아도 {@code @OrderBy("position ASC")} 덕분에 다시 읽으면 오름차순이어야 한다. */
	@Test
	void cascadesItemsAndReadsThemOrderedByPosition() {
		Digest digest = Digest.pending(TODAY, "<b>다이제스트</b>", GENERATED_AT);
		digest.addItem(item(3, "셋째", "example.com/3"));
		digest.addItem(item(1, "첫째", "example.com/1"));
		digest.addItem(item(2, "둘째", "example.com/2"));
		digests.save(digest);
		entityManager.flush();
		entityManager.clear();

		Digest reloaded = digests.findByDigestDate(TODAY).orElseThrow();
		assertEquals(List.of(1, 2, 3), reloaded.getItems().stream().map(DigestItem::getPosition).toList());
		assertEquals(List.of("첫째", "둘째", "셋째"), reloaded.getItems().stream().map(DigestItem::getTitleKo).toList());
		assertEquals(reloaded.getId(), reloaded.getItems().getFirst().getDigest().getId());
	}

	/** digest_date UNIQUE가 하루 1회 발송의 멱등성을 보장한다. */
	@Test
	void rejectsDuplicateDigestDate() {
		digests.saveAndFlush(Digest.pending(TODAY, "첫 번째", GENERATED_AT));

		Digest duplicate = Digest.pending(TODAY, "두 번째", GENERATED_AT);
		assertThrows(DataIntegrityViolationException.class, () -> digests.saveAndFlush(duplicate));
	}

	@Test
	void existsByDigestDateDetectsAlreadyGeneratedDay() {
		digests.saveAndFlush(Digest.pending(TODAY, "본문", GENERATED_AT));

		assertTrue(digests.existsByDigestDate(TODAY));
		assertFalse(digests.existsByDigestDate(TODAY.minusDays(1)));
	}

	@Test
	void markSentTurnsPendingIntoSent() {
		Digest digest = digests.save(Digest.pending(TODAY, "본문", GENERATED_AT));
		digest.markSent(SENT_AT);
		entityManager.flush();
		entityManager.clear();

		Digest reloaded = digests.findByDigestDate(TODAY).orElseThrow();
		assertEquals(DigestStatus.SENT, reloaded.getStatus());
		assertEquals(SENT_AT, reloaded.getSentAt());
		assertTrue(reloaded.isSent());
	}

	/**
	 * EMPTY는 발송해도 EMPTY로 남는다. SENT로 덮으면 "그날 뉴스가 없었다"는 정보가 사라져
	 * ADR-013의 점수 임계값을 튜닝할 근거가 없어진다 (ADR-014).
	 */
	@Test
	void markSentKeepsEmptyStatusAndOnlyFillsSentAt() {
		Digest digest = digests.save(Digest.empty(TODAY, "오늘의 AI 뉴스는 없습니다.", GENERATED_AT));
		digest.markSent(SENT_AT);
		entityManager.flush();
		entityManager.clear();

		Digest reloaded = digests.findByDigestDate(TODAY).orElseThrow();
		assertEquals(DigestStatus.EMPTY, reloaded.getStatus());
		assertEquals(SENT_AT, reloaded.getSentAt());
		assertTrue(reloaded.isSent());
	}

	@Test
	void newDigestIsNotSentYet() {
		Digest digest = digests.saveAndFlush(Digest.pending(TODAY, "본문", GENERATED_AT));

		assertFalse(digest.isSent());
		assertEquals(DigestStatus.PENDING, digest.getStatus());
		assertEquals(GENERATED_AT, digest.getGeneratedAt());
	}

	/**
	 * 기간 밖(7일 초과)과 미발송 다이제스트는 중복 제거 대상에서 빠진다. 미발송분을 제외하는 이유:
	 * 구독자에게 나가지 않은 기사는 다시 골라도 중복이 아니다 (아카이브 노출 기준과 동일하게 sentAt으로 판정).
	 */
	@Test
	void findsNormalizedUrlsAndTitlesOfSentDigestsWithinPeriod() {
		digests.save(sentDigest(TODAY.minusDays(9), "오래된 기사", "example.com/old"));
		digests.save(sentDigest(TODAY.minusDays(3), "최근 기사", "example.com/recent"));

		Digest unsent = Digest.pending(TODAY, "오늘 본문", GENERATED_AT);
		unsent.addItem(item(1, "아직 안 나간 기사", "example.com/unsent"));
		digests.save(unsent);

		entityManager.flush();
		entityManager.clear();

		LocalDate since = TODAY.minusDays(7);
		assertEquals(List.of("example.com/recent"), digests.findNormalizedUrlsSince(since));
		assertEquals(List.of("최근 기사"), digests.findTitlesSince(since));
	}

	private static Digest sentDigest(LocalDate date, String titleKo, String normalizedUrl) {
		Digest digest = Digest.pending(date, "본문", GENERATED_AT);
		digest.addItem(item(1, titleKo, normalizedUrl));
		digest.markSent(SENT_AT);
		return digest;
	}
}
