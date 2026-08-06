package com.example.ainewsdigest.collect;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 기사 본문 크롤링 설정.
 *
 * <p>{@code @Component}나 {@code @EnableConfigurationProperties}를 붙이지 않는다.
 * {@code AinewsdigestApplication}의 {@code @ConfigurationPropertiesScan}이 등록한다.
 *
 * @param maxContentLength 요약에 넘길 앞부분 길이. 넘으면 자른다
 * @param minContentLength 이보다 짧으면 추출 실패로 본다. 쿠키 배너·페이월 안내문만 긁힌 경우다
 * @param maxRedirects     직접 따라갈 리다이렉트 홉 수. 넘으면 포기한다
 * @param userAgent        신원을 밝힌다. 봇 차단 회피용 UA 로테이션을 하지 않는다
 */
@ConfigurationProperties("ainewsdigest.collect.extractor")
public record ExtractorProperties(Duration timeout, Integer maxContentLength, Integer minContentLength,
		Integer maxRedirects, String userAgent) {

	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
	private static final int DEFAULT_MAX_CONTENT_LENGTH = 3000;
	private static final int DEFAULT_MIN_CONTENT_LENGTH = 200;
	private static final int DEFAULT_MAX_REDIRECTS = 3;
	private static final String DEFAULT_USER_AGENT = "AINewsDigest/1.0 (+https://github.com/JongGurlHan)";

	public ExtractorProperties {
		timeout = (timeout == null) ? DEFAULT_TIMEOUT : timeout;
		maxContentLength = (maxContentLength == null) ? DEFAULT_MAX_CONTENT_LENGTH : maxContentLength;
		minContentLength = (minContentLength == null) ? DEFAULT_MIN_CONTENT_LENGTH : minContentLength;
		maxRedirects = (maxRedirects == null) ? DEFAULT_MAX_REDIRECTS : maxRedirects;
		userAgent = (userAgent == null || userAgent.isBlank()) ? DEFAULT_USER_AGENT : userAgent;
	}
}
