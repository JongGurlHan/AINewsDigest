package com.example.ainewsdigest.digest;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

	/**
	 * 아카이브는 항상 최신순이다. 요청 파라미터의 정렬을 그대로 쓰지 않는다 — {@code ?sort=id,asc} 하나로
	 * 목록 순서가 뒤집히면 "매일 쌓이고 있다"는 화면의 유일한 메시지가 깨진다.
	 */
	private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "digestDate");

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

	/**
	 * 랜딩에 바로 노출할 최근 발송분 (최신순). 설명보다 실물을 먼저 보여준다 (ADR-004).
	 *
	 * <p>{@link #findLatestSentWithContent()}와 달리 EMPTY도 포함한다. 랜딩은 "이 서비스가 매일 돌고
	 * 있다"를 보여주는 자리이고, 뉴스가 없던 날도 그날 돌았다는 기록이다.
	 */
	public List<DigestView> findRecentSent(int limit) {
		return toViews(this.digests.findSentIds(PageRequest.of(0, limit, NEWEST_FIRST)).getContent());
	}

	/** 아카이브 목록. 발송된 다이제스트만, 최신순으로, 항목까지 함께 담아 돌려준다. */
	public Page<DigestView> findSentPage(Pageable pageable) {
		Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST);
		Page<Long> ids = this.digests.findSentIds(newestFirst);
		return new PageImpl<>(toViews(ids.getContent()), newestFirst, ids.getTotalElements());
	}

	/** 아카이브 상세. 발송되지 않은 날짜는 없는 것으로 취급한다 — 컨트롤러가 404로 바꾼다. */
	public Optional<DigestView> findSentByDate(LocalDate date) {
		return this.digests.findSentByDateWithItems(date).stream().findFirst().map(DigestView::from);
	}

	/**
	 * id 목록을 항목까지 채운 뷰로 바꾼다. <b>쿼리는 id 개수와 무관하게 1회</b>다 — 목록 화면에서
	 * 20건을 그리며 항목을 건건이 조회하면 그것이 N+1이다.
	 *
	 * <p>fetch join 결과는 순서가 보장되지 않으므로 페이지가 확정한 id 순서로 다시 세운다.
	 */
	private List<DigestView> toViews(List<Long> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		Map<Long, Digest> byId = new HashMap<>();
		for (Digest digest : this.digests.findAllWithItemsByIds(ids)) {
			byId.putIfAbsent(digest.getId(), digest);
		}
		return ids.stream().map(byId::get).filter(Objects::nonNull).map(DigestView::from).toList();
	}
}
