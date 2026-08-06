package com.example.ainewsdigest.digest;

import com.example.ainewsdigest.delivery.TelegramProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웹 계층만 띄운다. 조회 규칙(발송분만 노출 등)은 {@link DigestQueryServiceTest}가 DB와 함께 검증하고,
 * 여기서는 <b>컨트롤러가 그 서비스만 부르는지, 템플릿이 받은 것을 어떻게 그리는지</b>를 본다.
 *
 * <p>{@code @EnableConfigurationProperties}가 필요한 이유: {@code @WebMvcTest}의 TypeExcludeFilter가
 * {@code @ConfigurationPropertiesScan}의 스캔까지 걸러내 {@link TelegramProperties} 빈이 등록되지 않는다.
 */
@WebMvcTest(DigestController.class)
@EnableConfigurationProperties(TelegramProperties.class)
class DigestControllerTest {

	private static final LocalDate DATE = LocalDate.of(2026, 8, 5);

	private static final String DISPLAY_DATE = "2026년 8월 5일 (수)";

	private static final Instant SENT_AT = Instant.parse("2026-08-04T22:30:00Z");

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private DigestQueryService digests;

	private static DigestItemView item(int position, String titleKo) {
		return new DigestItemView(position, titleKo, position + "번 기사의 한글 요약입니다.",
				"https://example.com/" + position, "example.com");
	}

	private static DigestView digest(LocalDate date, DigestItemView... items) {
		return new DigestView(date, DigestStatus.SENT, "<b>텔레그램용 본문</b>", SENT_AT, List.of(items));
	}

	@Test
	void landingShowsRecentDigests() throws Exception {
		given(this.digests.findRecentSent(anyInt()))
				.willReturn(List.of(digest(DATE, item(1, "클로드 코드 2.0 출시"))));

		String body = this.mvc.perform(get("/"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertTrue(body.contains("클로드 코드 2.0 출시"), body);
		assertTrue(body.contains(DISPLAY_DATE), body);
	}

	/** 구독은 딥링크 한 번으로 끝난다 (ADR-003). 이 링크가 죽으면 구독 경로 자체가 없다. */
	@Test
	void landingLinksTheTelegramDeepLink() throws Exception {
		given(this.digests.findRecentSent(anyInt())).willReturn(List.of());

		String body = this.mvc.perform(get("/"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertTrue(body.contains("https://t.me/ainewsdigest_bot?start=web"), body);
	}

	@Test
	void archiveListsDates() throws Exception {
		given(this.digests.findSentPage(any()))
				.willReturn(new PageImpl<>(List.of(digest(DATE, item(1, "커서 changelog 요약"))),
						PageRequest.of(0, 20), 1));

		String body = this.mvc.perform(get("/archive"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertTrue(body.contains(DISPLAY_DATE), body);
		assertTrue(body.contains("/archive/2026-08-05"), body);
	}

	@Test
	void detailRendersItems() throws Exception {
		given(this.digests.findSentByDate(DATE))
				.willReturn(Optional.of(digest(DATE, item(1, "첫째 기사"), item(2, "둘째 기사"))));

		String body = this.mvc.perform(get("/archive/2026-08-05"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertTrue(body.contains("첫째 기사"), body);
		assertTrue(body.contains("1번 기사의 한글 요약입니다."), body);
		assertTrue(body.contains("example.com"), body);
	}

	@Test
	void unknownDateIsNotFound() throws Exception {
		given(this.digests.findSentByDate(DATE)).willReturn(Optional.empty());

		this.mvc.perform(get("/archive/2026-08-05")).andExpect(status().isNotFound());
	}

	@Test
	void malformedDateIsBadRequest() throws Exception {
		this.mvc.perform(get("/archive/2026-13-99")).andExpect(status().isBadRequest());
		this.mvc.perform(get("/archive/yesterday")).andExpect(status().isBadRequest());
	}

	/**
	 * 미발송분은 어느 화면에도 없다. 컨트롤러는 발송분만 돌려주는 서비스 메서드 셋만 부르므로,
	 * 그 셋이 비면 화면도 비어야 한다 — 다른 경로로 새어 들어올 자리가 없다는 뜻이다.
	 * ({@code sentAt}으로 실제로 걸러지는지는 {@link DigestQueryServiceTest}가 DB로 확인한다.)
	 */
	@Test
	void unsentDigestIsNotExposedOnAnyPage() throws Exception {
		given(this.digests.findRecentSent(anyInt())).willReturn(List.of());
		given(this.digests.findSentPage(any())).willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
		given(this.digests.findSentByDate(DATE)).willReturn(Optional.empty());

		String landing = this.mvc.perform(get("/")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String archive = this.mvc.perform(get("/archive")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertTrue(landing.contains("아직 발송된 다이제스트가 없습니다."), landing);
		assertTrue(archive.contains("아직 발송된 다이제스트가 없습니다."), archive);
		this.mvc.perform(get("/archive/2026-08-05")).andExpect(status().isNotFound());
	}

	/**
	 * 상한이 없으면 Boot 기본값 2,000이 적용되어 {@code ?size=2000} 한 번에 다이제스트 2,000건과
	 * 그 항목 전량이 조회된다. 상한은 {@code spring.data.web.pageable.max-page-size}가 건다.
	 */
	@Test
	void pageSizeIsCappedBeforeReachingTheService() throws Exception {
		given(this.digests.findSentPage(any())).willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

		this.mvc.perform(get("/archive").param("size", "5000")).andExpect(status().isOk());

		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(this.digests).findSentPage(captor.capture());
		assertEquals(50, captor.getValue().getPageSize());
	}

	/** 역순으로 담긴 뷰를 넣어도 화면은 position 오름차순이다 (step 0의 {@code @OrderBy}와 짝이다). */
	@Test
	void detailRendersItemsInPositionOrder() throws Exception {
		given(this.digests.findSentByDate(DATE))
				.willReturn(Optional.of(digest(DATE, item(3, "셋째 기사"), item(1, "첫째 기사"), item(2, "둘째 기사"))));

		String body = this.mvc.perform(get("/archive/2026-08-05"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertTrue(body.indexOf("첫째 기사") < body.indexOf("둘째 기사"), body);
		assertTrue(body.indexOf("둘째 기사") < body.indexOf("셋째 기사"), body);
	}

	@Test
	void externalSourceLinksCarryNoopenerNoreferrer() throws Exception {
		given(this.digests.findSentByDate(DATE)).willReturn(Optional.of(digest(DATE, item(1, "기사"))));

		String body = this.mvc.perform(get("/archive/2026-08-05"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		int link = body.indexOf("https://example.com/1");
		assertTrue(link >= 0, body);
		assertTrue(body.indexOf("rel=\"noopener noreferrer\"", link) > link, body);
	}
}
