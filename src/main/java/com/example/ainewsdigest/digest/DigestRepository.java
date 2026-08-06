package com.example.ainewsdigest.digest;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
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

	/**
	 * 아카이브에 노출할 다이제스트의 id 페이지 (최신순).
	 *
	 * <p><b>{@code status}로 거르지 않는다.</b> EMPTY는 발송돼도 status가 EMPTY로 남으므로
	 * {@code status = SENT} 조건을 걸면 "뉴스가 없었던 날"이 아카이브에서 통째로 사라진다 (ADR-014).
	 * 노출 기준은 {@code sentAt} 하나다 — 미발송분은 아직 구독자에게 나가지 않았다.
	 *
	 * <p>엔티티가 아니라 <b>id만</b> 뽑는 이유: 페이징과 컬렉션 fetch join은 함께 쓸 수 없다.
	 * 같이 쓰면 Hibernate가 전 행을 읽어 메모리에서 자른다. id로 한 페이지를 확정한 뒤
	 * {@link #findAllWithItemsByIds}로 항목을 한 번에 채운다 (쿼리 2회 고정, N+1 아님).
	 */
	@Query(value = """
			select d.id
			from Digest d
			where d.sentAt is not null
			""",
			countQuery = """
					select count(d)
					from Digest d
					where d.sentAt is not null
					""")
	Page<Long> findSentIds(Pageable pageable);

	/** 다이제스트 여러 건을 항목까지 한 번에 읽는다. 순서는 호출자가 id 목록 순서로 복원한다. */
	@Query("""
			select d
			from Digest d
			left join fetch d.items
			where d.id in :ids
			""")
	List<Digest> findAllWithItemsByIds(@Param("ids") Collection<Long> ids);

	/**
	 * 아카이브 상세용. 발송된 날짜 하나를 항목까지 한 번에 읽는다.
	 *
	 * <p>{@code Optional}이 아니라 {@code List}로 받는다 — 컬렉션 fetch join은 루트 한 건에 대해
	 * 여러 행을 돌려줄 수 있어, 중복 제거가 어느 계층에서 일어나는지에 결과 타입이 좌우되면 안 된다.
	 */
	@Query("""
			select d
			from Digest d
			left join fetch d.items
			where d.digestDate = :date and d.sentAt is not null
			""")
	List<Digest> findSentByDateWithItems(@Param("date") LocalDate date);
}
