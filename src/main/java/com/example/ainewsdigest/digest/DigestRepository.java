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
}
