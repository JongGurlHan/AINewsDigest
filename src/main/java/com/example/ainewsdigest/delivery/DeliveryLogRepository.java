package com.example.ainewsdigest.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, Long> {

	/**
	 * 발송 재개용. 이 다이제스트에서 이미 발송에 성공한 구독자 ID 집합이다 (ADR-014).
	 *
	 * <p>{@code distinct}인 이유: {@code delivery_log}에는 DB 레벨 유일 제약이 없다. 재시도 실패 로그가
	 * (digest, subscriber)당 여러 건 쌓이는 설계라, 호출부가 집합으로 쓰는 이 결과는 여기서 중복을 없앤다.
	 */
	@Query("""
			select distinct l.subscriberId
			from DeliveryLog l
			where l.digestId = :digestId and l.status = :status
			""")
	List<Long> findSubscriberIdsByDigestIdAndStatus(@Param("digestId") Long digestId,
			@Param("status") DeliveryStatus status);
}
