# 프로젝트: {프로젝트명}

## 기술 스택
- Spring Boot 4.0.7
- Java 21 (Gradle toolchain), Gradle 9.5.1 (wrapper)
- Gradle, Kotlin DSL (`build.gradle.kts`)
- Spring MVC + Thymeleaf (서버 사이드 렌더링)
- Spring Data JPA + PostgreSQL
- Flyway (스키마 마이그레이션), 테스트 DB는 인메모리 H2
- 기본 패키지: `com.example.ainewsdigest`

- CRITICAL: Boot 4에서 의존성 좌표가 바뀌었다. Boot 3 관례를 쓰지 말 것:
  - `spring-boot-starter-web` (X) → `spring-boot-starter-webmvc` (O)
  - `spring-boot-starter-test` (X) → 모듈별 `spring-boot-starter-{webmvc,data-jpa,...}-test` (O)
  - Flyway는 전용 `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql`

## 아키텍처 규칙
- CRITICAL: {절대 지켜야 할 규칙 1 (예: Controller는 Service만 호출한다. Repository/EntityManager 직접 접근 금지)}
- CRITICAL: {절대 지켜야 할 규칙 2 (예: Entity를 Controller 응답이나 View 모델로 직접 반환하지 말 것. 반드시 DTO로 변환)}
- {일반 규칙 (예: 도메인별 패키지로 분리. controller/service/repository를 최상위로 쪼개지 말 것)}

## 개발 프로세스
- CRITICAL: 새 기능 구현 시 반드시 테스트를 먼저 작성하고, 테스트가 통과하는 구현을 작성할 것 (TDD)
- 커밋 메시지는 conventional commits 형식을 따를 것 (feat:, fix:, docs:, refactor:)

## 명령어
./gradlew bootRun    # 개발 서버 (기본 8080)
./gradlew build      # 컴파일 + test + check
./gradlew test       # 테스트만
./gradlew check      # 정적 분석 / 스타일 검사

> Windows cmd/PowerShell에서는 `gradlew.bat`, Git Bash에서는 `./gradlew`를 사용한다.
