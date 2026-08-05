package com.example.ainewsdigest.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 테스트용 PostgreSQL 컨테이너 설정. DB가 필요한 테스트에서 {@code @Import(TestcontainersConfig.class)}로 사용한다.
 *
 * <p>주의: Testcontainers 2.x에서 {@code PostgreSQLContainer}는 비제네릭이며 패키지가
 * {@code org.testcontainers.postgresql}로 바뀌었다. 1.x의
 * {@code org.testcontainers.containers.PostgreSQLContainer<SELF>}를 쓰지 말 것.
 *
 * <p><b>컨테이너를 static 싱글톤으로 두는 이유.</b> {@code @Bean}이 인스턴스를 새로 만들면
 * ApplicationContext마다 PostgreSQL 컨테이너가 하나씩 뜬다. 테스트 슬라이스가 다르면
 * ({@code @DataJpaTest} / {@code @SpringBootTest} / 페이크 구성이 다른 컨텍스트) 컨텍스트 캐시 키가
 * 갈라지므로, 빌드 한 번에 컨테이너가 대여섯 개 순차 기동한다. static 필드로 두면 JVM당 하나다.
 * 이 필드를 인스턴스 생성으로 되돌리지 말 것.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

	private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:16-alpine");

	static {
		CONTAINER.start();
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return CONTAINER;
	}
}
