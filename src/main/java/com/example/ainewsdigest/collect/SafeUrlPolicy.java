package com.example.ainewsdigest.collect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * 이 URL에 요청을 보내도 되는가 (ADR-017).
 *
 * <p><b>크롤링 대상 URL은 우리가 고른 것이 아니라 HN에 아무나 올린 것이다.</b> points 30을 넘기면
 * 임의의 주소를 우리 서버가 GET 하게 만들 수 있다. 노리는 곳은 {@code 169.254.169.254}(OCI 인스턴스
 * 메타데이터 — IMDSv1이 켜진 인스턴스는 인증 없이 응답한다), {@code 127.0.0.1:8080}(우리 앱 자신),
 * VCN 내부 호스트다. 게다가 받아온 본문은 LLM 요약을 거쳐 <b>공개 아카이브 페이지에 게시되고
 * 구독자 전원에게 발송된다</b> — 읽기만 되는 SSRF가 아니라 유출 경로까지 완성되어 있다.
 *
 * <p>상태가 없으므로 스레드 안전하다.
 *
 * <p><b>막지 못하는 것</b>: DNS rebinding(검사 통과 후 연결 시점에 다른 IP로 재해석되는 것).
 * 막으려면 해석된 IP로 직접 연결하고 Host 헤더와 TLS SNI를 손봐야 하는데 jsoup API로는 깔끔하지 않다.
 * ADR-017에 알려진 한계로 기록되어 있다.
 */
@Component
public class SafeUrlPolicy {

	private static final Logger log = LoggerFactory.getLogger(SafeUrlPolicy.class);

	/** {@code fc00::/7} 판정용. 상위 7비트가 {@code 1111110}이면 unique-local이다. */
	private static final int UNIQUE_LOCAL_IPV6_MASK = 0xFE;

	private static final int UNIQUE_LOCAL_IPV6_PREFIX = 0xFC;

	private final UrlNormalizer normalizer;

	private final HostResolver resolver;

	@Autowired
	public SafeUrlPolicy(UrlNormalizer normalizer) {
		this(normalizer, InetAddress::getAllByName);
	}

	/**
	 * 테스트용. 이름 해석을 갈아 끼워 <b>DNS 없이</b> 판정 로직을 검증하고, WireMock이 뜨는
	 * {@code localhost}를 공인 주소로 취급할 수 있게 한다. 운영에서는 위 생성자만 쓴다.
	 */
	SafeUrlPolicy(UrlNormalizer normalizer, HostResolver resolver) {
		this.normalizer = normalizer;
		this.resolver = resolver;
	}

	/**
	 * 네트워크로 나가기 직전마다(<b>리다이렉트 홉 포함</b>) 호출한다. 최초 URL에만 적용하는 것은
	 * 적용하지 않는 것과 같다 — {@code https://정상사이트/r} 이 {@code http://169.254.169.254/} 로
	 * 302 하는 순간 검사가 통째로 무의미해진다.
	 */
	public boolean isAllowed(String url) {
		if (!normalizer.isHttpUrl(url)) {
			log.debug("허용되지 않는 스킴이다 (url={})", url);
			return false;
		}
		String host = hostOf(url);
		if (host == null) {
			return false;
		}
		InetAddress[] addresses;
		try {
			addresses = resolver.resolve(host);
		}
		catch (UnknownHostException ex) {
			log.debug("호스트 이름을 해석하지 못했다 (host={})", host);
			return false;
		}
		if (addresses == null || addresses.length == 0) {
			return false;
		}
		// "하나라도"가 중요하다. A 레코드를 여러 개 걸어 하나만 정상 주소로 만들어 두면
		// 첫 번째 주소만 보는 검사는 통과한다.
		for (InetAddress address : addresses) {
			if (isInternal(address)) {
				log.debug("내부 주소로 해석된다 (host={}, address={})", host, address.getHostAddress());
				return false;
			}
		}
		return true;
	}

	private static boolean isInternal(InetAddress address) {
		return address.isLoopbackAddress()
				|| address.isLinkLocalAddress()
				|| address.isSiteLocalAddress()
				|| address.isAnyLocalAddress()
				|| address.isMulticastAddress()
				|| isUniqueLocalIpv6(address);
	}

	/** {@code isSiteLocalAddress()}는 {@code fc00::/7}을 잡지 못한다. 첫 바이트를 직접 확인한다. */
	private static boolean isUniqueLocalIpv6(InetAddress address) {
		if (!(address instanceof Inet6Address)) {
			return false;
		}
		byte[] bytes = address.getAddress();
		return bytes.length > 0 && (bytes[0] & UNIQUE_LOCAL_IPV6_MASK) == UNIQUE_LOCAL_IPV6_PREFIX;
	}

	private static String hostOf(String url) {
		try {
			return new URI(url.trim()).getHost();
		}
		catch (URISyntaxException ex) {
			return null;
		}
	}

	/** 이름 해석. 테스트가 DNS 없이 판정을 검증할 수 있게 분리한다. */
	@FunctionalInterface
	interface HostResolver {

		InetAddress[] resolve(String host) throws UnknownHostException;
	}
}
