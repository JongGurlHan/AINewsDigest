# 프로젝트: AI News Digest

개발자에게 유용한 AI 소식을 매일 오전 7시 30분(KST) 텔레그램으로 발송하는 뉴스 다이제스트 서비스.

## 기술 스택
- Spring Boot 4.0.7
- Java 21 (Gradle toolchain), Gradle 9.5.1 (wrapper)
- Gradle, Kotlin DSL (`build.gradle.kts`)
- Spring MVC + Thymeleaf (서버 사이드 렌더링)
- Spring Data JPA + PostgreSQL
- Flyway (스키마 마이그레이션), 테스트 DB는 Testcontainers PostgreSQL (H2 금지 — ADR-011)
- 기본 패키지: `com.example.ainewsdigest`

- CRITICAL: Boot 4에서 의존성 좌표가 바뀌었다. Boot 3 관례를 쓰지 말 것:
  - `spring-boot-starter-web` (X) → `spring-boot-starter-webmvc` (O)
  - `spring-boot-starter-test` (X) → 모듈별 `spring-boot-starter-{webmvc,data-jpa,...}-test` (O)
  - Flyway는 전용 `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql`

## 아키텍처 규칙
- CRITICAL: Controller는 Service만 호출한다. Repository/EntityManager 직접 접근 금지
- CRITICAL: Entity를 Controller 응답이나 View 모델로 직접 반환하지 말 것. 반드시 DTO로 변환
- CRITICAL: 외부 API 호출(OpenAI, Telegram, HN, RSS, 기사 크롤링)을 `@Transactional` 안에서 하지 말 것. DB 커넥션을 수 초간 점유한다. 호출 → 결과 확보 → 그 다음에 트랜잭션을 열어 저장한다
- CRITICAL: 외부 연동은 반드시 인터페이스 뒤에 둔다. 서비스 계층은 구현체(`OpenAiClient`, `TelegramClient` 등)를 직접 참조하지 않는다. 테스트는 인메모리 페이크로 대체할 수 있어야 한다
- 도메인별 패키지로 분리한다. `controller`/`service`/`repository`를 최상위로 쪼개지 말 것
- 도메인은 5개로 고정한다: `subscription`, `digest`, `collect`, `curation`, `delivery` (+ 공통 `global`). 새 최상위 패키지를 임의로 만들지 말 것

## 개발 프로세스
- CRITICAL: 새 기능 구현 시 반드시 테스트를 먼저 작성하고, 테스트가 통과하는 구현을 작성할 것 (TDD)
- 커밋 메시지는 conventional commits 형식을 따를 것 (feat:, fix:, docs:, refactor:)

## 명령어
./gradlew bootRun    # 개발 서버 (기본 8080)
./gradlew build      # 컴파일 + test + check
./gradlew test       # 테스트만
./gradlew check      # 정적 분석 / 스타일 검사

> Windows cmd/PowerShell에서는 `gradlew.bat`, Git Bash에서는 `./gradlew`를 사용한다.
