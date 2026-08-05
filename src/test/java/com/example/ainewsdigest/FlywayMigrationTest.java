package com.example.ainewsdigest;

import com.example.ainewsdigest.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway 마이그레이션이 실제 PostgreSQL에 적용되는지 검증한다. (ADR-011)
 *
 * <p>이 테스트를 삭제하지 말 것. 마이그레이션이 조용히 실행되지 않아도 다른 테스트는 전부 통과하며,
 * 그 경우 배포 시점 {@code ddl-auto: validate}에서야 앱이 뜨지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class FlywayMigrationTest {

	@Autowired
	private DataSource dataSource;

	@Test
	void migrationCreatesAllTables() throws Exception {
		Set<String> tables = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
			 ResultSet rs = connection.getMetaData()
					 .getTables(null, "public", "%", new String[]{"TABLE"})) {
			while (rs.next()) {
				tables.add(rs.getString("TABLE_NAME").toLowerCase());
			}
		}

		// flyway_schema_history의 존재가 Flyway가 실제로 실행되었음을 증명한다.
		assertTrue(tables.contains("flyway_schema_history"),
				"Flyway가 실행되지 않았다. 확인된 테이블: " + tables);

		for (String expected : new String[]{"subscriber", "digest", "digest_item", "delivery_log"}) {
			assertTrue(tables.contains(expected),
					"테이블 " + expected + " 없음. 확인된 테이블: " + tables);
		}
	}
}
