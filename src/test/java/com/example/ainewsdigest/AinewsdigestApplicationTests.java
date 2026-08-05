package com.example.ainewsdigest;

import com.example.ainewsdigest.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Import(TestcontainersConfig.class)
class AinewsdigestApplicationTests {

	@Autowired
	private RestClient.Builder restClientBuilder;

	@Test
	void contextLoads() {
	}

	/**
	 * 모든 외부 어댑터(수집·OpenAI·텔레그램)가 이 빌더를 주입받아 쓴다. Boot 4에서
	 * {@code spring-boot-starter-webmvc}는 RestClient를 가져오지 않으므로
	 * {@code spring-boot-starter-restclient}가 빠지면 여기서 먼저 깨진다 (ADR-015).
	 */
	@Test
	void restClientBuilderIsAutoConfigured() {
		assertNotNull(restClientBuilder);
	}

}
