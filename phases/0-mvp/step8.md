# Step 8: subscription-flow

## 읽어야 할 파일

- `/docs/ARCHITECTURE.md` — "구독 (상시)" 데이터 흐름
- `/docs/ADR.md` — ADR-003(딥링크 구독), ADR-008(롱폴링)
- `/docs/PRD.md` — 핵심 기능 1번(구독)
- `/src/main/java/com/example/ainewsdigest/subscription/` — step 0의 `Subscriber`, `SubscriberRepository`
- `/src/main/java/com/example/ainewsdigest/delivery/` — step 7의 `Messenger`, `UpdateSource`, `SendResult`, `TelegramUpdate`
- `/src/main/java/com/example/ainewsdigest/digest/DigestRepository.java` — 최근호 조회에 쓴다

## 작업

텔레그램 업데이트를 롱폴링으로 받아 구독·해지를 처리한다.

### 구독 서비스

`com.example.ainewsdigest.subscription.SubscriptionService`

```java
/** /start 처리. 신규면 등록, 기존 해지자면 재활성화. 이미 활성이면 상태를 바꾸지 않는다. */
@Transactional
public SubscribeOutcome start(long chatId, String payload);

/** /stop 처리. 미등록이거나 이미 해지 상태면 아무 일도 하지 않는다. */
@Transactional
public void stop(long chatId);

public enum SubscribeOutcome { NEW, REACTIVATED, ALREADY_ACTIVE }
```

**반드시 멱등이어야 한다.** 같은 `chatId`로 `/start`가 여러 번 와도 구독자 행이 하나만 생겨야 한다. 이유: 아래 폴러의 offset이 유실되면 텔레그램이 최근 24시간 업데이트를 다시 보내준다.

### 봇 명령 처리기

`com.example.ainewsdigest.delivery.BotCommandHandler`

```java
/** 업데이트 하나를 처리한다. 예외를 던지지 않는다. */
public void handle(TelegramUpdate update);
```

| 입력 | 동작 |
|---|---|
| `/start` (결과 `NEW` 또는 `REACTIVATED`) | 환영 메시지 발송 → **가장 최근 다이제스트를 이어서 한 통 더 발송** (없으면 안내 문구) |
| `/start` (결과 `ALREADY_ACTIVE`) | "이미 구독 중입니다" 한 통만. **최근 다이제스트를 다시 보내지 않는다** |
| `/stop` | `SubscriptionService.stop()` → 해지 확인 메시지 |
| `/help` | 안내 메시지 |
| 그 외 | 안내 메시지(`/help`와 동일)를 보낸다 |

- 명령 앞뒤 공백을 제거하고, `/start@봇이름` 형태도 `/start`로 인식한다

**`SubscribeOutcome`으로 분기하라.** 같은 `/start`가 여러 번 들어오는 것은 예외 상황이 아니라 일상이다 — 사용자가 버튼을 두 번 누르거나, 폴러 offset이 유실돼 텔레그램이 미확인 업데이트를 재전송하거나, 앱이 재시작 루프에 빠지면 그렇게 된다. 그때마다 최근호를 다시 쏘면 같은 사람에게 같은 다이제스트가 반복 발송되고, 재시작 루프에서는 증폭돼 **텔레그램 레이트리밋에 걸린다.** `SubscribeOutcome`은 정확히 이 분기를 위해 정의된 값이다.

### `/start` 페이로드는 무제한 사용자 입력이다

```java
/** /start 뒤 문자열을 Subscriber.source에 넣을 수 있는 형태로 정제한다. */
private String sanitizePayload(String raw);
```

- `[A-Za-z0-9_-]` 외의 문자를 제거하고 **50자로 자른다**. 정제 후 비면 `null`
- `subscriber.source`는 `varchar(50)`이다

딥링크 규격(1~64자, `[A-Za-z0-9_-]`)은 **텔레그램 클라이언트가 만드는 링크에만 적용된다.** 사용자가 대화창에 `/start ` + 4,096자를 직접 치는 것은 아무것도 막지 않고, 한글·이모지·따옴표도 얼마든지 들어온다. 정제 없이 저장하면 제약 위반 예외가 나는데, `handle()`이 예외를 삼키도록 되어 있어 **구독이 조용히 실패한다.** 사용자는 환영 메시지조차 받지 못하고 무엇이 잘못됐는지 알 방법이 없다.
- 최근 다이제스트는 **`sentAt != null` 이면서 `status != EMPTY`인 것 중 `digestDate`가 가장 큰 것**을 쓴다
  - `sentAt == null`인 것을 보내지 마라 — 아직 발송 전이라 오늘 아침에 또 받게 된다
  - `EMPTY`를 보내지 마라 — 환영 메시지 직후에 "오늘의 AI 뉴스는 없습니다"가 이어지면 첫인상이 망가진다. 한 칸 더 거슬러 올라가 내용이 있는 다이제스트를 보낸다
  - 발송 여부를 `status == SENT`로 판정하지 마라. EMPTY는 발송돼도 status가 EMPTY로 남는다 (ARCHITECTURE.md "다이제스트 상태 규칙")

### 이 조회는 `digest` 도메인에 둔다

`BotCommandHandler`에서 `DigestRepository`를 직접 호출하지 마라. **`com.example.ainewsdigest.digest.DigestQueryService`를 이 step에서 만들고** 거기에 둔다:

```java
// digest/DigestQueryService.java  — 조회 전용, 모든 메서드에 @Transactional(readOnly = true)
public Optional<DigestView> findLatestSentWithContent();   // sentAt != null && status != EMPTY, digestDate 최대
```

이유: step 10의 웹 화면이 **같은 규칙**으로 최신호를 노출한다. 여기서 리포지토리를 직접 부르면 같은 쿼리가 두 벌 생기고, 나중에 한쪽만 고쳐져 봇과 웹이 서로 다른 다이제스트를 보여준다. step 10은 이 서비스에 화면용 메서드를 **추가**한다 (ARCHITECTURE.md "도메인 간 접근").

`DigestView`/`DigestItemView` DTO도 이 step에서 만든다 (정의는 step 10과 동일하게 맞춘다). 엔티티를 `delivery`로 넘기지 마라 — `open-in-view: false`라 트랜잭션 밖에서 `items`를 건드리면 `LazyInitializationException`이 난다.

### 롱폴링 러너

`com.example.ainewsdigest.delivery.TelegramUpdatePoller`

- 애플리케이션 기동 후 **데몬 스레드 하나**에서 루프를 돈다. `SmartLifecycle` 또는 `ApplicationRunner` + `ExecutorService`를 쓴다
- 루프: `updateSource.getUpdates(offset, pollTimeoutSeconds)` → 결과를 분기한다
  - `PollResult.Updates` → 각 업데이트를 `BotCommandHandler.handle()`에 넘기고 `offset = maxUpdateId + 1`, 연속 실패 카운터를 0으로
  - `PollResult.Failure` → 연속 실패 카운터를 올리고 **지수 백오프**만큼 쉰 뒤 재시도. offset은 그대로 둔다
- **빈 `Updates`에는 쉬지 마라.** 롱폴링에서 업데이트 0건은 가장 흔한 정상 응답이다. 여기서 5초를 자면 응답성이 그만큼 나빠진다. 쉬는 것은 `Failure`일 때뿐이다

**연속 실패 카운터를 실제로 써라.** 카운터를 올려놓고 고정 5초만 쉬면 그 값은 아무 의미가 없고, 텔레그램 장애가 길어질 때 **시간당 720회**를 계속 때린다.

- 백오프 = `min(failure-backoff × 2^(연속실패-1), max-backoff)` → 5s, 10s, 20s, 40s, 60s, 60s…
- `Updates` 수신 시 카운터와 백오프를 리셋한다
- 연속 실패가 `alert-threshold`(기본 20회 ≈ 10분)에 도달하면 **ERROR 로그를 한 번만** 남긴다. 복구될 때까지 반복하지 마라 — 장애 중에 로그·알림이 쏟아지면 그게 또 다른 장애다. 복구되면(카운터 리셋) 플래그도 내린다
  - **여기서는 로그까지만 한다.** 관리자 알림을 보내는 `AdminNotifier`는 step 11에서 만들어지므로, step 11이 이 지점을 알림으로 연결한다. "임계값 도달 시 1회"라는 판정 로직은 이 step에서 완성해 두고, 알림 연결만 나중에 붙인다
- offset은 **메모리에만 보관한다.** 재시작하면 0부터 시작해 최근 24시간 업데이트를 다시 받게 되지만, 위에서 멱등하게 만들었으므로 문제되지 않는다
- 개별 업데이트 처리 중 예외가 나도 **루프를 멈추지 마라.** 로그만 남기고 다음 업데이트로 넘어간다
- 애플리케이션 종료 시 루프를 깨끗하게 중단한다
- **테스트 프로파일에서는 폴러가 뜨지 않아야 한다.** `@ConditionalOnProperty`로 켜고 끌 수 있게 만들고 기본값은 켬, 테스트 설정에서 끈다.
  이유: 테스트마다 백그라운드 스레드가 실제 텔레그램 API를 때리면 안 된다

```yaml
ainewsdigest:
  telegram:
    polling:
      enabled: true          # src/test/resources/application.yml 에서는 false
      failure-backoff: 5s    # PollResult.Failure일 때만 적용된다 (지수 백오프의 시작값)
      max-backoff: 60s
      alert-threshold: 20    # 연속 실패 이 횟수에 도달하면 관리자에게 1회 알린다
```

## 테스트

`src/test/java/com/example/ainewsdigest/subscription/SubscriptionServiceTest.java` (Testcontainers + `@SpringBootTest`)

1. 신규 `chatId`로 `start()` → 구독자가 ACTIVE로 생성되고 `source`에 payload가 저장된다
2. 같은 `chatId`로 `start()`를 3번 호출해도 구독자 행은 1개다 (멱등)
3. `stop()` 후 `start()` → REACTIVATED, 상태 ACTIVE, `consecutiveFailures`가 0으로 초기화된다
4. 미등록 `chatId`로 `stop()` 호출 시 예외가 나지 않는다
5. **200자 payload로 `/start` 해도 구독자가 ACTIVE로 생성되고 `source`가 50자 이하다**
6. **한글·이모지·따옴표가 섞인 payload도 예외 없이 처리되고, 정제 후 비면 `source`가 null이다**

`src/test/java/com/example/ainewsdigest/delivery/BotCommandHandlerTest.java` (페이크 `Messenger` 사용)

7. `/start` 처리 시 환영 메시지와 최근 다이제스트, 총 2통이 발송된다
8. 발송된 다이제스트가 없으면 환영 메시지 1통만 나가고 예외가 없다
9. 미발송(`sentAt == null`) 다이제스트만 있으면 그것을 보내지 않는다
10. 가장 최근 발송분이 `EMPTY`면 그것을 건너뛰고 그 이전의 내용 있는 다이제스트를 보낸다
11. **같은 `chatId`로 `/start`를 3번 보내면 최근 다이제스트는 1회만 발송된다** (2·3번째는 `ALREADY_ACTIVE`라 안내 한 통뿐)
12. `/start@my_bot` 도 `/start`로 인식된다
13. `/stop` 처리 시 상태가 UNSUBSCRIBED로 바뀌고 확인 메시지가 나간다
14. 알 수 없는 명령에는 안내 메시지가 나간다

`TelegramUpdatePoller`는 페이크 `UpdateSource`로 검증한다:

15. 업데이트 3건을 받으면 핸들러가 3번 호출되고 다음 offset이 `maxUpdateId + 1`이다
16. 핸들러가 예외를 던져도 나머지 업데이트가 계속 처리된다
17. `PollResult.Failure`를 받으면 백오프 후 재시도하고 **offset이 변하지 않는다**
18. **빈 `Updates`에는 백오프하지 않는다** (실패와 정상 무응답이 다르게 처리되는지 검증)
19. **연속 실패 시 백오프가 5s → 10s → 20s로 늘고 `max-backoff`에서 멈춘다** (대기 시간을 직접 재지 말고, 주입한 슬리퍼(sleeper)가 받은 값을 기록해 검증한다)
20. **`Updates` 수신 시 백오프가 초기값으로 리셋된다**
21. **연속 실패가 임계값에 도달하면 ERROR 로그가 1회만 남는다** (그 뒤 10번 더 실패해도 추가 로그 없음), 복구 후 다시 임계값에 도달하면 또 1회 남는다

## Acceptance Criteria

```bash
./gradlew build   # Git Bash 기준. PowerShell/cmd에서는 gradlew.bat build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다. (Docker Desktop이 실행 중이어야 한다)
2. 아키텍처 체크리스트를 확인한다:
   - ARCHITECTURE.md 디렉토리 구조를 따르는가?
   - ADR 기술 스택을 벗어나지 않았는가?
   - CLAUDE.md CRITICAL 규칙을 위반하지 않았는가?
3. 결과에 따라 `phases/0-mvp/index.json`의 step 8을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 테스트에서 폴러가 실제로 돌게 두지 마라. 이유: 백그라운드 스레드가 실제 API를 호출하거나 테스트를 불안정하게 만든다. 테스트 설정에서 반드시 끈다
- offset을 DB에 저장하는 테이블을 만들지 마라. 이유: 처리를 멱등하게 만들었으므로 불필요하다. 스키마와 마이그레이션만 늘어난다
- 폴링 루프에서 예외가 나면 루프를 종료시키지 마라. 이유: 한 번의 네트워크 오류로 구독 기능이 영구 정지된다
- 텔레그램 업데이트에서 온 문자열을 정제 없이 저장하지 마라. 이유: 딥링크의 64자·문자 종류 제한은 **클라이언트가 만드는 링크에만** 적용된다. 사용자는 대화창에 무엇이든 칠 수 있고, 제약 위반 예외는 `handle()`이 삼켜서 구독이 조용히 실패한다
- `/start`마다 최근 다이제스트를 무조건 보내지 마라. 이유: 재시작이나 중복 입력으로 같은 사람에게 같은 내용이 반복 발송되고, 재시작 루프에서는 증폭돼 레이트리밋에 걸린다. `SubscribeOutcome`으로 분기한다
- 실패 백오프를 고정값으로 두지 마라. 이유: 연속 실패 카운터가 아무 데도 쓰이지 않는 죽은 값이 되고, 장애가 길어지면 시간당 720회를 계속 때린다
- 폴링 실패 알림을 매 사이클 보내지 마라. 이유: 장애 중에 알림이 쏟아지면 그 자체가 2차 장애다. 임계값 도달 시 1회만 보낸다
- 테스트에서 실제로 백오프 시간만큼 기다리지 마라. 이유: 테스트가 수십 초로 늘어난다. 대기를 주입 가능한 인터페이스로 빼고 호출된 값만 검증한다
- 사용자별 관심 키워드 설정 같은 개인화 명령을 추가하지 마라. 이유: PRD MVP 제외 사항이다
- 웹훅 엔드포인트를 만들지 마라. 이유: ADR-008에서 롱폴링으로 결정했다
- 일일 다이제스트 발송 로직을 만들지 마라. 이유: step 9의 범위다
- `BotCommandHandler`에서 `DigestRepository`를 직접 호출하지 마라. 이유: step 10이 같은 조회를 또 구현하게 되어 봇과 웹이 어긋난다. `DigestQueryService`에 둔다
- `Digest`/`DigestItem` 엔티티를 `delivery` 패키지로 넘기지 마라. 이유: `open-in-view: false`라 트랜잭션 밖에서 `items`를 읽으면 터진다. DTO로 변환해 넘긴다
- 기존 테스트를 깨뜨리지 마라
