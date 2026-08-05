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
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:16-alpine");
	}
}
