plugins {
	java
	id("org.springframework.boot") version "4.0.7"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")

	// 기사 본문 추출(HTML) / RSS·Atom 파싱
	implementation("org.jsoup:jsoup:1.21.1")
	implementation("com.rometools:rome:2.1.0")

	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")

	// 테스트 DB는 실제 PostgreSQL 컨테이너를 쓴다 (H2 아님 — ADR-011)
	// Testcontainers 2.x에서 모듈 아티팩트명이 바뀌었다: postgresql -> testcontainers-postgresql.
	// 1.x 좌표(org.testcontainers:postgresql, org.testcontainers:junit-jupiter)는 2.x에 존재하지 않는다.
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")

	// 외부 HTTP 어댑터는 HTTP 레벨에서 검증한다
	testImplementation("org.wiremock:wiremock-standalone:3.13.1")

	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
