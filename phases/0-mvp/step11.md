# Step 11: scheduler-ops

## 읽어야 할 파일

- `/docs/ARCHITECTURE.md` — **"스케줄링" 절 전체. 타임존 규칙이 여기 있다**
- `/docs/ADR.md` — ADR-010(관리자 알림 + 데드맨스위치)
- `/docs/PRD.md` — 발송 규칙(07:30, 주말 포함)
- `/src/main/java/com/example/ainewsdigest/digest/DigestGenerationService.java` — step 6
- `/src/main/java/com/example/ainewsdigest/delivery/DigestSendService.java` — step 9
- `/src/main/java/com/example/ainewsdigest/delivery/` — step 7의 `Messenger` (관리자 알림에 재사용)

## 작업

배치를 시각에 맞춰 돌리고, 실패를 감지해 알린다.

### 스케줄러

`com.example.ainewsdigest.global.DigestScheduler`

```java
@Scheduled(cron = "0 0 7 * * *", zone = "Asia/Seoul")
public void generateDaily();

@Scheduled(cron = "0 15 7 * * *", zone = "Asia/Seoul")
public void retryGenerate();

@Scheduled(cron = "0 30 7 * * *", zone = "Asia/Seoul")
public void sendDaily();
```

**`zone = "Asia/Seoul"`을 반드시 명시한다.** 운영 서버(Oracle Cloud VM)의 기본 타임존은 UTC다. 존을 빼면 한국 시간 오후 4시에 발송된다. `@EnableScheduling`을 어딘가에 켜야 한다.

- 주말·공휴일 구분 없이 매일 돈다 (cron에 요일 제한을 넣지 마라)
- `generateDaily()`: `LocalDate.now(ZoneId.of("Asia/Seoul"))` 기준으로 `DigestGenerationService.generate()` 호출
- `sendDaily()`: 같은 날짜로 `DigestSendService.send()` 호출

### 07:15 생성 재시도

`generate()`는 오늘자 다이제스트가 이미 있으면 아무것도 하지 않고 반환한다(step 6 절차 1). 그래서 재시도는 **같은 메서드를 한 번 더 부르는 것**으로 끝나며, 정상일에는 조회 한 번으로 즉시 끝나 무해하다.

- 07:00이 실패했으면 failure 알림은 그때 이미 나갔다. 07:15가 **성공하면 복구됐다는 warning을 보낸다** — 관리자가 앞선 알림을 보고 새벽에 일어나지 않도록
- 07:15도 실패하면 failure를 한 번 더 보낸다. 이때는 07:30 발송도 실패할 것이 확정이다

이것이 ARCHITECTURE가 말한 "생성 실패 시 30분의 복구 여유"를 실제로 쓰는 유일한 장치다. 없으면 07:00 실패 = 그날 발송 없음이고, 복구 수단이 SSH 수동 실행뿐이라 이 프로젝트의 철학("사람이 개입해야만 복구되는 구조는 만들지 않는다")과 정면으로 어긋난다.

### 스케줄러 스레드 풀은 1로 둔다

`spring.task.scheduling.pool.size: 1` (Boot 기본값, `application.yml`에 명시되어 있다). **늘리지 마라.**

생성이 길어져 07:30을 넘겨도 발송은 동시 실행되지 않고 생성이 끝난 뒤 지연 실행된다. 이게 옳은 동작이다. 풀을 늘리면 생성 중에 발송이 시작돼 다이제스트를 찾지 못하고 "다이제스트 없음" 오탐 알림이 간다. 작업이 셋으로 늘어난 뒤로 이 순차성이 더 중요해졌다.

**날짜는 반드시 `Asia/Seoul` 기준으로 구한다.** `LocalDate.now()`를 인자 없이 호출하면 서버 기본 타임존(UTC)이 쓰여 07:00 KST에는 아직 전날이다. 그러면 생성과 발송이 서로 다른 날짜를 보게 되어 매일 "다이제스트 없음"이 된다.

### 관리자 알림

`com.example.ainewsdigest.global.AdminNotifier`

```java
public void notifyFailure(String title, String detail);
public void notifyWarning(String title, String detail);
```

- step 7의 `Messenger`로 `admin-chat-id`에 보낸다
- **step 8의 `TelegramUpdatePoller`가 남겨 둔 자리를 여기서 연결한다.** 폴러는 연속 실패가 임계값에 도달하면 ERROR 로그를 1회 남기도록 만들어져 있다(`AdminNotifier`가 그때는 없었다). 그 지점에 `notifyFailure`를 연결하되, **"1회만"이라는 판정 로직은 폴러에 이미 있으므로 새로 만들지 마라**
- `admin-chat-id`가 비어 있으면 조용히 넘어간다 (예외를 던지지 마라)
- **알림 전송 자체가 실패해도 예외를 던지지 마라.** 이유: 장애 알림이 실패해서 2차 장애를 만들면 안 된다. 로그만 남긴다
- 메시지에 HTML 특수문자가 들어갈 수 있으므로 이스케이프한다

알릴 상황:
| 상황 | 종류 |
|---|---|
| 생성 중 예외 발생 (07:00) | failure |
| 생성 재시도 성공 (07:15) | warning — "복구됨" |
| 생성 재시도도 실패 (07:15) | failure |
| **수집 전면 실패 의심** (`candidateCount == 0` && `failedSourceCount > 0`) | **failure** |
| **일부 소스 장애** (`failedSourceCount > 0`, 후보는 있음) | **warning** |
| 발송 시 해당 날짜 다이제스트 없음 (`digestFound == false`) | failure |
| 발송 중 예외 발생 | failure |
| **발송 전원 실패** (`sentAtRecorded == false`) | **failure** |
| 발송 실패 구독자가 시도분의 30% 이상 (`failed / totalSubscribers`) | warning |

**발송 전원 실패는 반드시 failure로 알려라.** `sentAtRecorded == false`는 다이제스트가 미발송 상태로 남아 재실행을 기다린다는 뜻이다(ADR-018). 실패율 30% 경고와 같은 등급으로 묻으면, 아무도 받지 못한 날이 "실패율이 좀 높았던 날"로 보인다.

**수집 전면 실패를 반드시 알려라.** 소스가 전부 죽으면 후보 0건 → EMPTY 다이제스트 → 07:30에 "오늘의 AI 뉴스는 없습니다"가 정상 발송 → 발송이 성공했으니 `pingSuccess()`까지 나간다. **관리자 알림도 없고 데드맨스위치도 초록불이라 장애를 아무도 모른다.** ADR-010이 "침묵과 장애가 구분되지 않는다"며 막으려던 상황이 정확히 이것이다. `candidateCount == 0 && failedSourceCount == 0`(진짜 조용한 날)과 구분하는 것이 핵심이다 — 전자만 알린다.

### 데드맨스위치

`com.example.ainewsdigest.global.HealthPinger`

```java
public void pingSuccess();
```

- 설정된 URL로 HTTP GET을 한 번 보낸다 (healthchecks.io 형식)
- URL이 비어 있으면 아무것도 하지 않는다
- **실패해도 예외를 던지지 마라.** 발송 자체에 영향을 주면 안 된다
- **발송이 성공적으로 끝난 뒤에만** 호출한다. 생성 단계에서 호출하면 발송이 실패해도 핑이 가서 감시가 무의미해진다

이유(ADR-010): 앱이 죽으면 실패 알림조차 못 보낸다. "뉴스 없음"을 정상 동작으로 설계했기 때문에 침묵과 장애가 구분되지 않는다. 성공 시 핑을 보내고 외부가 그 부재를 감지하는 역방향 감시가 필요하다.

### 설정

```yaml
ainewsdigest:
  scheduler:
    enabled: true          # src/test/resources/application.yml 에서는 false
  ops:
    healthcheck-url: ${HEALTHCHECK_URL:}
```

**테스트 프로파일에서는 스케줄러가 뜨지 않아야 한다.** `@ConditionalOnProperty`로 제어한다. 이유: 테스트 중 배치가 돌면 실제 외부 API를 호출한다.

### 수동 트리거 (운영 편의)

생성/발송을 수동으로 돌릴 수 있어야 한다. 새벽에 실패했을 때 SSH로 복구하기 위해서다.
**HTTP 엔드포인트를 만들지 말고**(인증이 없어 아무나 호출할 수 있다), `ApplicationRunner`에서 읽는 커맨드라인 인자로 처리한다.

```
java -jar app.jar --ainewsdigest.run=generate
java -jar app.jar --ainewsdigest.run=send
```

인자가 없으면 평소처럼 서버로 뜬다.

## 테스트

`src/test/java/com/example/ainewsdigest/global/`

1. `DigestScheduler.generateDaily()`가 `Asia/Seoul` 기준 오늘 날짜로 생성 서비스를 호출한다 (`Clock`을 주입해 고정 시각으로 검증)
2. 생성 중 예외가 나면 `AdminNotifier.notifyFailure`가 호출된다
3. `digestFound == false`면 실패 알림이 간다
4. 발송 성공 후 `HealthPinger.pingSuccess()`가 호출된다
5. **발송이 실패하면 `pingSuccess()`가 호출되지 않는다**
6. 실패율 30% 이상이면 경고 알림이 간다
7. `admin-chat-id`가 비어 있으면 `AdminNotifier`가 예외 없이 무시한다
8. `Messenger`가 예외를 던져도 `AdminNotifier`가 예외를 전파하지 않는다
9. `HealthPinger`가 500을 받아도 예외를 던지지 않는다 (WireMock)
10. **`candidateCount == 0` && `failedSourceCount > 0`이면 failure 알림이 간다** (수집 전면 실패)
11. **`candidateCount == 0` && `failedSourceCount == 0`이면 알림이 가지 않는다** (진짜 뉴스 없는 날은 정상이다)
12. `failedSourceCount > 0`인데 후보가 있으면 warning 알림이 간다
13. **`retryGenerate()`가 오늘자 다이제스트가 이미 있으면 생성 서비스를 호출하되 아무 알림도 보내지 않는다** (정상일에 무해)
14. **07:00 실패 후 07:15가 성공하면 "복구됨" warning이 간다**
15. **07:15도 실패하면 failure가 간다**
16. **`sentAtRecorded == false`면 failure 알림이 간다** (전원 실패 — ADR-018)
17. `retryGenerate()`도 `Asia/Seoul` 기준 날짜를 쓴다 (고정 `Clock`으로 검증)

`Clock`을 빈으로 등록해 주입받아라. 시각에 의존하는 로직을 `Instant.now()` 직접 호출로 만들면 테스트할 수 없다.

## Acceptance Criteria

```bash
./gradlew build   # Git Bash 기준. PowerShell/cmd에서는 gradlew.bat build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다. (Docker Desktop이 실행 중이어야 한다)
2. 아키텍처 체크리스트를 확인한다:
   - ARCHITECTURE.md 스케줄링 규칙(zone 명시)을 따르는가?
   - ADR 기술 스택을 벗어나지 않았는가?
   - CLAUDE.md CRITICAL 규칙을 위반하지 않았는가?
3. 결과에 따라 `phases/0-mvp/index.json`의 step 11을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `@Scheduled`에서 `zone`을 생략하지 마라. 이유: 서버 기본 타임존이 UTC라 9시간 어긋난다. 이 프로젝트에서 가장 흔하게 터질 실수다
- `LocalDate.now()`를 인자 없이 호출하지 마라. 이유: 위와 같은 이유로 생성과 발송이 다른 날짜를 보게 된다. 반드시 `Asia/Seoul` 존을 넘긴다
- 배치를 트리거하는 HTTP 엔드포인트를 만들지 마라. 이유: 인증이 없어 누구나 발송을 유발할 수 있다. 커맨드라인 인자로 처리한다
- 테스트에서 스케줄러가 실제로 돌게 두지 마라. 이유: 테스트 중 외부 API를 호출한다
- 알림·핑 실패를 예외로 전파하지 마라. 이유: 감시 장치의 실패가 본 기능을 망가뜨리면 안 된다
- cron에 요일 제한을 넣지 마라. 이유: PRD에 주말 포함 매일 발송으로 명시되어 있다
- 새 도메인 로직을 만들지 마라. 이유: 이 step은 기존 서비스를 시각에 맞춰 호출하고 결과를 알리는 것까지다
- 후보 0건을 무조건 정상으로 처리하지 마라. 이유: 수집 전면 장애와 구분되지 않아 ADR-010의 감시가 통째로 무력화된다. `failedSourceCount`로 갈라야 한다
- `spring.task.scheduling.pool.size`를 늘리지 마라. 이유: 생성이 끝나기 전에 발송이 시작돼 "다이제스트 없음" 오탐이 난다. 07:00 생성이 길어지면 발송은 지연 실행되는 것이 맞다
- `retryGenerate()`에 별도의 멱등성 검사를 만들지 마라. 이유: `generate()`가 이미 `existsByDigestDate`로 막고 있다. 검사가 두 벌이 되면 규칙이 갈라진다
- cron·zone을 yml 설정 키로 빼지 마라. 이유: `@Scheduled` 어노테이션의 리터럴을 읽는 코드만 있으면 된다. 아무도 읽지 않는 키는 죽은 키다 (step 4가 `min-items`를 두지 않은 것과 같은 원칙). 발송 시각은 제품 결정이라 바뀌면 재빌드해도 무방하다
- 발송 전원 실패를 실패율 경고로 뭉뚱그리지 마라. 이유: 그날 다이제스트가 미발송으로 남아 재실행을 기다리는 상태다. 경고 등급으로 묻으면 아무도 못 받은 날이 "실패율이 좀 높았던 날"로 보인다
- 기존 테스트를 깨뜨리지 마라
