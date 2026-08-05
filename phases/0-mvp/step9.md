# Step 9: digest-send

## 읽어야 할 파일

- `/docs/ARCHITECTURE.md` — **"다이제스트 발송" 데이터 흐름 5단계, 트랜잭션 경계(구독자 단위 분리)**
- `/docs/PRD.md` — 발송 규칙
- `/src/main/java/com/example/ainewsdigest/delivery/` — step 7의 `Messenger`·`SendResult`, step 0의 `DeliveryLog`·`DeliveryLogRepository`
- `/src/main/java/com/example/ainewsdigest/subscription/` — `Subscriber`, `SubscriberRepository`
- `/src/main/java/com/example/ainewsdigest/digest/` — `Digest`, `DigestRepository`

## 작업

저장된 PENDING 다이제스트를 활성 구독자 전원에게 발송한다.

### 파일

`com.example.ainewsdigest.delivery.DigestSendService`

```java
/** 해당 날짜의 PENDING 다이제스트를 발송한다. */
public SendSummary send(LocalDate date);

public record SendSummary(
        boolean digestFound,
        int totalSubscribers,
        int succeeded,
        int failed,
        int autoUnsubscribed
) {}
```

### 절차

1. `digestRepository.findByDigestDate(date)` 조회
   - 없으면 `SendSummary(digestFound=false, ...)` 반환 (스케줄러가 관리자에게 알린다)
   - 이미 `SENT`면 아무것도 하지 않고 반환한다 (**멱등성**)
   - `EMPTY`도 발송 대상이다. `messageText`에 "오늘의 AI 뉴스는 없습니다."가 들어 있다
2. `subscriberRepository.findAllByStatus(ACTIVE)` 조회
3. 구독자를 순회하며 `messenger.send(chatId, digest.messageText())` 호출
4. 결과별 처리:

| `SendResult` | 처리 |
|---|---|
| `Success` | `DeliveryLog(SUCCESS)` 기록, `subscriber.recordDeliverySuccess()` |
| `Blocked` (403) | `DeliveryLog(FAILED, "403")` 기록, **`subscriber.unsubscribe()`** — 죽은 구독자에게 매일 재시도하지 않는다 |
| `RateLimited` (429) | `retryAfterSeconds`만큼 대기 후 **같은 구독자에게 재시도**. 최대 2회 |
| `Failed(retryable=true)` | 지수 백오프(1s, 2s, 4s)로 최대 3회 재시도. 끝내 실패면 `DeliveryLog(FAILED)`, `subscriber.recordDeliveryFailure()` |
| `Failed(retryable=false)` | 재시도 없이 `DeliveryLog(FAILED)` 기록 |

5. 연속 실패가 임계값(설정, 기본 5)을 넘은 구독자는 자동으로 `unsubscribe()` 한다
6. 전부 끝나면 `digest.markSent(now)` 저장

### 발송 속도 제한

텔레그램은 초당 약 30건까지 허용한다. 구독자 순회 사이에 짧은 간격을 두어 **초당 20건을 넘지 않게** 한다. 설정으로 조절 가능하게 만든다.

### 트랜잭션 경계 — 반드시 지킬 것

**`send()` 전체를 하나의 트랜잭션으로 감싸지 마라.** (CLAUDE.md CRITICAL, ARCHITECTURE.md 트랜잭션 경계)

- 텔레그램 HTTP 호출은 트랜잭션 **밖**에서 한다
- `DeliveryLog` 기록과 `Subscriber` 상태 변경은 **구독자 한 명 단위로 짧은 트랜잭션**에서 커밋한다
- 이유: 100번째 구독자에서 실패했다고 앞의 99건 발송 기록이 롤백되면, 다시 실행할 때 그 99명에게 중복 발송된다

### 설정

```yaml
ainewsdigest:
  delivery:
    max-per-second: 20
    max-retries: 3
    rate-limit-retries: 2
    auto-unsubscribe-after-failures: 5
```

## 테스트

`src/test/java/com/example/ainewsdigest/delivery/DigestSendServiceTest.java`
Testcontainers + `@SpringBootTest`, `Messenger`는 **페이크로 교체**한다. 결과를 시나리오별로 반환하도록 만든다.

1. 활성 구독자 3명 전원에게 발송되고 `DeliveryLog`가 3건 SUCCESS로 남는다
2. 발송 후 `Digest.status == SENT`, `sentAt`이 채워진다
3. **이미 SENT인 다이제스트로 다시 호출하면 발송이 0건이다** (멱등)
4. 해당 날짜 다이제스트가 없으면 `digestFound == false`이고 예외가 나지 않는다
5. `EMPTY` 상태 다이제스트도 발송된다
6. 한 구독자가 403(`Blocked`)이면 **그 구독자만 UNSUBSCRIBED**가 되고 나머지는 정상 발송된다
7. 403으로 해지된 구독자의 `DeliveryLog`가 FAILED + errorCode "403"으로 남는다
8. `Failed(retryable=true)`가 3번 반복되면 3회 시도 후 포기하고 다음 구독자로 넘어간다
9. 429 후 성공하면 최종적으로 SUCCESS로 기록된다
10. **중간 구독자에서 실패해도 앞선 구독자의 `DeliveryLog`가 남아 있다** (트랜잭션 분리 검증)
11. `consecutiveFailures`가 임계값을 넘으면 자동 해지된다
12. UNSUBSCRIBED 구독자에게는 발송하지 않는다

## Acceptance Criteria

```bash
./gradlew build   # Git Bash 기준. PowerShell/cmd에서는 gradlew.bat build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다. (Docker Desktop이 실행 중이어야 한다)
2. 아키텍처 체크리스트를 확인한다:
   - ARCHITECTURE.md 데이터 흐름·트랜잭션 경계를 따르는가?
   - ADR 기술 스택을 벗어나지 않았는가?
   - CLAUDE.md CRITICAL 규칙을 위반하지 않았는가?
3. 결과에 따라 `phases/0-mvp/index.json`의 step 9를 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `send()` 전체에 `@Transactional`을 붙이지 마라. 이유: 위에 설명했다. 부분 실패 시 중복 발송을 유발한다
- 테스트에서 실제 텔레그램 API를 호출하지 마라. 이유: 페이크 `Messenger`로 모든 분기를 검증할 수 있다
- 테스트에서 실제로 `Thread.sleep`으로 재시도 백오프를 기다리게 만들지 마라. 이유: 테스트가 수십 초로 늘어난다. 대기 시간을 설정값으로 빼고 테스트에서는 0으로 낮춰라
- 다이제스트를 생성하지 마라. 이유: step 6의 범위다. 이 step은 저장된 것을 읽어 보내기만 한다
- `@Scheduled`를 붙이지 마라. 이유: step 11의 범위다
- 발송 실패 시 관리자에게 알리는 로직을 여기 넣지 마라. 이유: step 11의 범위다. 이 step은 `SendSummary`를 반환하는 것까지만 한다
- 기존 테스트를 깨뜨리지 마라
