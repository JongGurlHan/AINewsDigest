package com.example.ainewsdigest.digest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DigestRepository extends JpaRepository<Digest, Long> {

	Optional<Digest> findByDigestDate(LocalDate date);

	boolean existsByDigestDate(LocalDate date);

	/**
	 * 최근 N일간 발송된 항목의 정규화 URL. 후보 중복 제거에 쓴다.
	 *
	 * <p>{@code sentAt}으로 거르는 이유: 구독자에게 나가지 않은 기사는 다시 골라도 중복이 아니다.
	 * 아카이브 노출 기준과 같은 축을 쓴다 (ADR-014).
	 */
	@Query("""
			select i.normalizedUrl
			from Digest d join d.items i
			where d.digestDate >= :since and d.sentAt is not null
			""")
	List<String> findNormalizedUrlsSince(@Param("since") LocalDate since);

	/** 최근 N일간 발송된 항목의 한글 제목. LLM 선별 프롬프트에 중복 배제용으로 전달한다. */
	@Query("""
			select i.titleKo
			from Digest d join d.items i
			where d.digestDate >= :since and d.sentAt is not null
			""")
	List<String> findTitlesSince(@Param("since") LocalDate since);

	/**
	 * 가장 최근에 발송됐고 내용이 있는 다이제스트. 봇의 {@code /start} 응답과 웹 랜딩이 함께 쓴다.
	 *
	 * <p><b>호출은 {@code DigestQueryService}를 거친다.</b> 규칙(발송 판정 축 + EMPTY 제외)이 두 도메인에
	 * 각자 복제되면 봇과 웹이 서로 다른 것을 보여주게 된다 (ARCHITECTURE "도메인 간 접근").
	 */
	@Query("""
			select d
			from Digest d
			where d.sentAt is not null and d.status <> :excluded
			order by d.digestDate desc
			limit 1
			""")
	Optional<Digest> findLatestSentWithContent(@Param("excluded") DigestStatus excluded);
}
