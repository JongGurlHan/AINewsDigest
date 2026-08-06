package com.example.ainewsdigest.digest;

import com.example.ainewsdigest.delivery.TelegramProperties;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 랜딩·아카이브 3화면 (ADR-004). 구독은 딥링크 한 번으로 끝나므로(ADR-003) 이 컨트롤러에 폼도
 * 상태 변경도 없다 — 전부 조회다.
 *
 * <p><b>{@link DigestQueryService}만 호출한다.</b> 리포지토리를 직접 부르지 않는다 (CLAUDE.md CRITICAL).
 * 노출 기준({@code sentAt != null})도 서비스에 있다 — 화면마다 조건을 다시 쓰면 한 곳만 틀려도
 * 아직 발송되지 않은 다이제스트가 웹에 먼저 뜬다.
 */
@Controller
public class DigestController {

	/** 랜딩에 바로 펼쳐 보여줄 발송분 수. */
	private static final int LANDING_DIGESTS = 5;

	/**
	 * 목록 한 페이지의 기본 크기. 상한은 {@code spring.data.web.pageable.max-page-size: 50}이 건다 —
	 * 명시하지 않으면 Boot 기본 상한 2,000이 적용되어 {@code ?size=2000} 한 번에 다이제스트 2,000건과
	 * 그 항목 전량이 조회된다.
	 */
	private static final int ARCHIVE_PAGE_SIZE = 20;

	private final DigestQueryService digests;

	private final String subscribeUrl;

	public DigestController(DigestQueryService digests, TelegramProperties telegram) {
		this.digests = digests;
		this.subscribeUrl = "https://t.me/%s?start=web".formatted(telegram.botUsername());
	}

	/** 헤더의 구독 링크가 모든 화면에 있으므로 모델에도 모든 화면에 넣는다. */
	@ModelAttribute("subscribeUrl")
	String subscribeUrl() {
		return this.subscribeUrl;
	}

	@GetMapping("/")
	String landing(Model model) {
		model.addAttribute("digests", this.digests.findRecentSent(LANDING_DIGESTS));
		return "digest/landing";
	}

	@GetMapping("/archive")
	String archive(@PageableDefault(size = ARCHIVE_PAGE_SIZE) Pageable pageable, Model model) {
		model.addAttribute("page", this.digests.findSentPage(pageable));
		return "digest/archive";
	}

	@GetMapping("/archive/{date}")
	String detail(@PathVariable("date") String date, Model model) {
		DigestView digest = this.digests.findSentByDate(parseDate(date))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "다이제스트를 찾을 수 없습니다."));
		model.addAttribute("digest", digest);
		return "digest/detail";
	}

	/**
	 * {@code yyyy-MM-dd}만 받는다. 프레임워크의 변환 실패 처리에 맡기지 않고 직접 400을 만드는 이유:
	 * "형식 오류는 400"은 이 화면의 요구사항이고, 기본 동작에 기대면 예외 처리 설정이 한 번 바뀔 때
	 * 조용히 500으로 변한다.
	 */
	private static LocalDate parseDate(String date) {
		try {
			return LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
		}
		catch (DateTimeParseException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "날짜 형식은 yyyy-MM-dd 입니다.");
		}
	}
}
