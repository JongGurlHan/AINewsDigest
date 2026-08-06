package com.example.ainewsdigest.collect;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 중복 판정에 쓸 URL 정규화기. 같은 기사가 소스마다 다른 URL로 들어오거나 며칠에 걸쳐
 * 반복 등장하는 것을 막는 1차 방어선이다 ({@code digest_item.normalized_url}과 대조).
 *
 * <p>네트워크를 쓰지 않는 순수 함수다. 배치 한 번에 수십 번 호출되므로 리다이렉트 추적이나
 * canonical URL 조회는 하지 않는다. 목적지 IP 검사는 크롤링 시점의 {@code SafeUrlPolicy}가 맡는다(ADR-017).
 *
 * <p>상태가 없으므로 스레드 안전하다.
 */
@Component
public class UrlNormalizer {

	private static final String HTTP = "http";
	private static final String HTTPS = "https";

	/** 같은 기사인데 유입 경로만 다르게 만드는 파라미터들. {@code utm_} 접두사는 별도로 걸러낸다. */
	private static final Set<String> TRACKING_PARAMS =
			Set.of("fbclid", "gclid", "ref", "ref_src", "source", "mc_cid", "mc_eid");

	private static final String TRACKING_PREFIX = "utm_";

	private static final Set<Integer> DEFAULT_PORTS = Set.of(80, 443);

	private static final Comparator<String> BY_PARAM_NAME = Comparator.comparing(UrlNormalizer::paramName);

	/**
	 * 중복 판정에 쓸 정규화 형태를 만든다.
	 * 파싱 불가능한 입력이면 원본을 트림해서 그대로 돌려준다 (예외를 던지지 않는다).
	 *
	 * <p>검증기가 아니다. {@code javascript:alert(1)}을 넣으면 그대로 나온다.
	 * 스킴 판별은 {@link #isHttpUrl(String)}이 파이프라인 진입점에서 따로 한다.
	 */
	public String normalize(String rawUrl) {
		if (rawUrl == null) {
			return "";
		}
		String trimmed = rawUrl.trim();
		URI uri = parse(trimmed);
		if (uri == null || !hasHttpScheme(uri) || uri.getHost() == null) {
			return trimmed;
		}

		StringBuilder normalized = new StringBuilder(HTTPS).append("://").append(hostOf(uri));
		int port = uri.getPort();
		if (port != -1 && !DEFAULT_PORTS.contains(port)) {
			normalized.append(':').append(port);
		}
		normalized.append(normalizePath(uri.getRawPath()));

		String query = normalizeQuery(uri.getRawQuery());
		if (!query.isEmpty()) {
			normalized.append('?').append(query);
		}
		return normalized.toString();
	}

	/** 표시용 도메인. www. 를 떼고 소문자로 반환한다. 파싱 실패 시 빈 문자열. */
	public String extractDomain(String rawUrl) {
		URI uri = parse(rawUrl);
		if (uri == null || uri.getHost() == null) {
			return "";
		}
		return hostOf(uri);
	}

	/** scheme이 http 또는 https이고 host가 있으면 true. 그 외(javascript:, file:, data:, 파싱 실패)는 false. */
	public boolean isHttpUrl(String rawUrl) {
		URI uri = parse(rawUrl);
		return uri != null && hasHttpScheme(uri) && uri.getHost() != null;
	}

	private static URI parse(String rawUrl) {
		if (rawUrl == null) {
			return null;
		}
		try {
			return new URI(rawUrl.trim());
		}
		catch (URISyntaxException ex) {
			return null;
		}
	}

	private static boolean hasHttpScheme(URI uri) {
		String scheme = uri.getScheme();
		if (scheme == null) {
			return false;
		}
		String lower = scheme.toLowerCase(Locale.ROOT);
		return HTTP.equals(lower) || HTTPS.equals(lower);
	}

	private static String hostOf(URI uri) {
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		return host.startsWith("www.") ? host.substring(4) : host;
	}

	/**
	 * 경로의 대소문자는 보존한다. 상당수 사이트에서 경로는 대소문자를 구분하므로
	 * 소문자화하면 서로 다른 문서가 같은 것으로 판정된다.
	 *
	 * <p>루트는 항상 {@code /}로 맞춘다. {@code https://a.com}과 {@code https://a.com/}이
	 * 다른 값이 되면 같은 글이 두 번 나갈 수 있다.
	 */
	private static String normalizePath(String rawPath) {
		if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) {
			return "/";
		}
		return rawPath.endsWith("/") ? rawPath.substring(0, rawPath.length() - 1) : rawPath;
	}

	/** 추적 파라미터를 버리고 남은 것을 이름 오름차순으로 정렬한다. 전부 버려지면 빈 문자열(= {@code ?}도 지운다). */
	private static String normalizeQuery(String rawQuery) {
		if (rawQuery == null || rawQuery.isEmpty()) {
			return "";
		}
		return Arrays.stream(rawQuery.split("&"))
				.filter(param -> !param.isBlank())
				.filter(param -> !isTracking(paramName(param)))
				.sorted(BY_PARAM_NAME)
				.collect(Collectors.joining("&"));
	}

	private static String paramName(String param) {
		int separator = param.indexOf('=');
		return separator < 0 ? param : param.substring(0, separator);
	}

	private static boolean isTracking(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.startsWith(TRACKING_PREFIX) || TRACKING_PARAMS.contains(lower);
	}
}
