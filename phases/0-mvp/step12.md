# Step 12: deploy-ci

## 읽어야 할 파일

- `/docs/ADR.md` — ADR-001(Oracle ARM64 무료티어), ADR-011(Testcontainers)
- `/docs/PRD.md` — 제약(예산), MVP 제외 사항(자동 배포 CD는 제외)
- `/build.gradle.kts` — 빌드 산출물과 의존성
- `/src/main/resources/application.yml` — 주입해야 할 환경변수 목록
- 이전 step들이 추가한 `ainewsdigest.*` 설정 키 전체 (환경변수로 뺄 것을 파악하기 위해)

## 작업

컨테이너 배포 구성과 테스트 CI를 만든다. **자동 배포(CD)는 만들지 않는다** — PRD MVP 제외 사항이다.

### Dockerfile

멀티스테이지 빌드. 베이스 이미지는 **ARM64(aarch64)에서 동작하는 것**을 쓴다 — 운영 서버가 Oracle Cloud ARM 인스턴스다.

```dockerfile
# 빌드 스테이지: eclipse-temurin:21-jdk
#   - gradle wrapper로 bootJar 실행
#   - 의존성 레이어 캐시를 위해 build.gradle.kts/settings.gradle.kts를 먼저 복사
# 실행 스테이지: eclipse-temurin:21-jre
#   - non-root 사용자로 실행
#   - TZ=Asia/Seoul 설정
#   - ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- 빌드 스테이지에서 **테스트를 실행하지 마라** (`bootJar`만). 이유: 도커 빌드 안에서는 Testcontainers가 쓸 Docker 데몬이 없다. 테스트는 CI와 로컬에서 돈다
- 컨테이너 안에서 `TZ=Asia/Seoul`을 설정한다. 스케줄러는 `zone`을 명시하지만, 로그 타임스탬프를 읽기 쉽게 하려면 필요하다

### docker-compose.yml

서비스 2개: `app`, `db`

```yaml
# db:  postgres:16-alpine
#   - 볼륨으로 데이터 영속화 (컨테이너를 지워도 발송 이력이 남아야 한다)
#   - healthcheck: pg_isready
# app:
#   - depends_on: db (condition: service_healthy)
#   - restart: unless-stopped
#   - 환경변수 주입 (아래)
#   - 포트 8080 노출
```

`app`이 `db`보다 먼저 떠서 커넥션 실패로 죽는 걸 막기 위해 **healthcheck 기반 `depends_on`을 쓴다.** `restart: unless-stopped`로 서버 재부팅 후 자동 기동되게 한다.

### 환경변수

`.env.example` 파일을 만들어 필요한 변수를 문서화한다. **`.env`는 `.gitignore`에 추가한다.**

```
DB_URL / DB_USER / DB_PASSWORD
OPENAI_API_KEY
TELEGRAM_BOT_TOKEN
TELEGRAM_BOT_USERNAME
ADMIN_CHAT_ID
HEALTHCHECK_URL
```

`.env.example`에 실제 값을 넣지 마라. 플레이스홀더만 둔다.

### GitHub Actions

`.github/workflows/ci.yml`

- 트리거: `push`, `pull_request`
- `ubuntu-latest` (Docker 내장 → Testcontainers가 그대로 동작한다)
- JDK 21 (temurin), Gradle 캐시 사용
- `./gradlew build` 실행
- 테스트 리포트를 아티팩트로 업로드 (실패 시 원인 파악용)

**이미지 빌드나 배포 job을 만들지 마라.** 이유: PRD MVP 제외 사항이며, GitHub Actions 러너는 x86_64인데 운영 서버는 ARM64라 크로스 빌드(buildx)와 레지스트리 설정이 필요하다. 지금 범위가 아니다.

### README.md

기존 `HELP.md`는 그대로 두고 `README.md`를 새로 만든다. 포트폴리오로 보일 문서이므로 다음을 담는다:

- 프로젝트 한 줄 소개와 서비스 URL 자리(플레이스홀더)
- 스크린샷 자리(플레이스홀더)
- 아키텍처 요약: 수집 → 선별 → 크롤링 → 요약 → 발송 파이프라인 다이어그램(텍스트)
- 기술 스택
- 주요 설계 결정 3~4개와 근거 (`docs/ADR.md` 링크)
- 로컬 실행 방법 (Docker Desktop 필요, 환경변수 설정, `docker compose up`)
- 테스트 실행 방법
- CI 배지

## Acceptance Criteria

```bash
./gradlew build             # Git Bash 기준. PowerShell/cmd에서는 gradlew.bat build
docker build -t ainewsdigest:local .
docker compose config       # compose 파일 문법 검증
```

세 커맨드가 모두 성공해야 한다.

## 검증 절차

1. 위 AC 커맨드 3개를 실행한다. (Docker Desktop이 실행 중이어야 한다)
2. 아키텍처 체크리스트를 확인한다:
   - ADR-001(ARM64 대상)을 고려한 베이스 이미지인가?
   - PRD MVP 제외 사항(자동 배포)을 지켰는가?
   - 시크릿이 커밋되지 않았는가?
3. 결과에 따라 `phases/0-mvp/index.json`의 step 12를 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 자동 배포(CD) job을 만들지 마라. 이유: PRD MVP 제외 사항이다. ARM 크로스 빌드와 SSH 배포는 별도 작업이다
- Docker 빌드 스테이지에서 테스트를 실행하지 마라. 이유: 빌드 컨테이너 안에 Docker 데몬이 없어 Testcontainers가 실패한다
- `.env`나 실제 API 키·봇 토큰을 커밋하지 마라. 이유: 즉시 유출이다. `.env.example`에는 플레이스홀더만 넣는다
- `application.yml`에 시크릿 기본값을 넣지 마라. 이유: 같은 이유다. 전부 환경변수 참조로 둔다
- 애플리케이션 코드를 수정하지 마라. 이유: 이 step은 배포·CI 구성만 다룬다. 빌드가 깨지면 원인을 찾아 보고하되, 기능 코드를 고쳐야 한다면 `error`로 기록하라
- `latest` 태그의 베이스 이미지를 쓰지 마라. 이유: 재현 불가능한 빌드가 된다. 버전을 고정한다
- 기존 테스트를 깨뜨리지 마라
