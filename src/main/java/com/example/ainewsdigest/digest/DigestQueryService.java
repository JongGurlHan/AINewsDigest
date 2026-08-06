package com.example.ainewsdigest.digest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 다이제스트 조회 전용 서비스. <b>클래스 레벨 {@code readOnly = true}가 모든 메서드에 적용된다</b> —
 * step 10이 화면용 메서드를 추가할 때 어노테이션을 빠뜨려도 조회 트랜잭션 밖으로 새지 않는다.
 *
 * <p><b>재사용되는 조회는 소유 도메인에만 둔다</b> (ARCHITECTURE "도메인 간 접근"). "가장 최근에 발송된
 * 내용 있는 다이제스트"는 봇의 {@code /start} 응답(step 8)과 웹 랜딩(step 10)이 같이 쓴다. 각자
 * {@code DigestRepository}를 직접 부르면 같은 규칙이 두 벌 생기고, 나중에 한쪽만 고쳐져
 * <b>봇과 웹이 서로 다른 다이제스트를 보여준다.</b>
 *
 * <p>반환은 엔티티가 아니라 DTO다. {@code open-in-view: false}라 호출자가 트랜잭션 밖에서
 * {@code items}를 건드리면 {@code LazyInitializationException}이 난다.
 */
@Service
@Transactional(readOnly = true)
public class DigestQueryService {

	private final DigestRepository digests;

	public DigestQueryService(DigestRepository digests) {
		this.digests = digests;
	}

	/**
	 * 가장 최근에 <b>발송됐고 내용이 있는</b> 다이제스트.
	 *
	 * <p>조건 둘을 모두 지켜야 한다:
	 * <ul>
	 *   <li>{@code sentAt != null} — 아직 안 나간 다이제스트를 여기서 보내면 구독자가 오늘 아침에 또 받는다.
	 *       {@code status == SENT}로 판정하면 안 된다. EMPTY는 발송돼도 status가 EMPTY로 남는다 (ADR-014)</li>
	 *   <li>{@code status != EMPTY} — 환영 메시지 바로 뒤에 "오늘의 AI 뉴스는 없습니다"가 이어지면 첫인상이
	 *       망가진다. 한 칸 더 거슬러 올라가 내용이 있는 날을 보낸다</li>
	 * </ul>
	 */
	public Optional<DigestView> findLatestSentWithContent() {
		return this.digests.findLatestSentWithContent(DigestStatus.EMPTY).map(DigestView::from);
	}
}
