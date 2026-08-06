package com.example.ainewsdigest.collect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 네트워크 없이 판정만 검증한다. IP 리터럴과 {@code localhost}는 해석에 DNS가 필요 없고,
 * 이름 해석이 필요한 경우는 {@link SafeUrlPolicy.HostResolver}를 갈아 끼운다.
 *
 * <p>실제 DNS에 의존하는 테스트를 만들지 않는다 — CI가 외부에 종속되고, 해석 결과가 바뀌면
 * 코드를 건드리지 않았는데 빌드가 깨진다.
 */
class SafeUrlPolicyTest {

	/** 공인 IP 리터럴. 이 값 자체를 해석하는 데는 DNS가 필요 없다. */
	private static final String PUBLIC_IP = "93.184.216.34";

	/** OCI 인스턴스 메타데이터. IMDSv1이 켜진 인스턴스는 인증 없이 응답한다. */
	@Test
	void rejectsTheCloudMetadataAddress() {
		assertFalse(policy().isAllowed("http://169.254.169.254/opc/v1/instance/"));
	}

	/** 우리 앱 자신과, 로컬에서만 열린 포트 전부. */
	@ParameterizedTest
	@ValueSource(strings = {
			"http://127.0.0.1:8080/actuator",
			"http://localhost:8080/",
			"http://127.0.0.53/",
			"http://[::1]/"
	})
	void rejectsLoopbackAddresses(String url) {
		assertFalse(policy().isAllowed(url), url);
	}

	/** VCN 내부의 다른 호스트. */
	@ParameterizedTest
	@ValueSource(strings = {
			"http://10.0.0.5/",
			"http://192.168.1.1/",
			"http://172.16.0.1/"
	})
	void rejectsSiteLocalAddresses(String url) {
		assertFalse(policy().isAllowed(url), url);
	}

	/** {@code isSiteLocalAddress()}가 잡지 못하는 IPv6 대역이라 첫 바이트를 직접 본다. */
	@ParameterizedTest
	@ValueSource(strings = {
			"http://[fc00::1]/",
			"http://[fd12:3456:789a::1]/"
	})
	void rejectsIpv6UniqueLocalAddresses(String url) {
		assertFalse(policy().isAllowed(url), url);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://0.0.0.0/",
			"http://[::]/",
			"http://224.0.0.1/",
			"http://169.254.1.1/",
			"http://[fe80::1]/"
	})
	void rejectsWildcardMulticastAndLinkLocalAddresses(String url) {
		assertFalse(policy().isAllowed(url), url);
	}

	/**
	 * 스킴 판정은 {@link UrlNormalizer#isHttpUrl(String)}을 재사용한다. 같은 판정을 두 벌 구현하면
	 * 한쪽만 고쳐질 때 step 2의 수집 관문과 여기가 서로 다른 것을 통과시킨다.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"file:///etc/passwd",
			"javascript:alert(1)",
			"data:text/html,x",
			"ftp://example.com/x",
			"gopher://example.com/",
			"not a url"
	})
	void rejectsNonHttpSchemes(String url) {
		assertFalse(policy().isAllowed(url), url);
	}

	@Test
	void rejectsNullAndBlankUrls() {
		assertFalse(policy().isAllowed(null));
		assertFalse(policy().isAllowed(""));
	}

	/** 정상 경로가 막히면 크롤링이 통째로 죽는다. */
	@Test
	void allowsOrdinaryPublicUrls() {
		SafeUrlPolicy policy = policy(host -> InetAddress.getAllByName(PUBLIC_IP));

		assertTrue(policy.isAllowed("https://example.com/article"));
		assertTrue(policy.isAllowed("http://example.com/article?id=1"));
	}

	/**
	 * <b>"하나라도"가 중요하다.</b> A 레코드를 여러 개 걸어 하나만 정상 주소로 만들어 두면
	 * 첫 번째 주소만 보는 검사는 통과한다.
	 */
	@Test
	void rejectsWhenAnyResolvedAddressIsInternal() {
		SafeUrlPolicy policy = policy(host -> new InetAddress[] {
				InetAddress.getByName(PUBLIC_IP),
				InetAddress.getByName("169.254.169.254")
		});

		assertFalse(policy.isAllowed("https://attacker.example/article"));
	}

	/** 이름 해석 자체가 실패하면 거부한다. */
	@Test
	void rejectsUnresolvableHosts() {
		SafeUrlPolicy policy = policy(host -> {
			throw new UnknownHostException(host);
		});

		assertFalse(policy.isAllowed("https://no-such-host.example/article"));
	}

	@Test
	void rejectsHostsThatResolveToNothing() {
		SafeUrlPolicy policy = policy(host -> new InetAddress[0]);

		assertFalse(policy.isAllowed("https://empty.example/article"));
	}

	private static SafeUrlPolicy policy() {
		return new SafeUrlPolicy(new UrlNormalizer());
	}

	private static SafeUrlPolicy policy(SafeUrlPolicy.HostResolver resolver) {
		return new SafeUrlPolicy(new UrlNormalizer(), resolver);
	}
}
