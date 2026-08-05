# Step 7: telegram-client

## 읽어야 할 파일

- `/docs/ARCHITECTURE.md` — 발송 흐름의 오류 분기(403/429/5xx), 구독 흐름
- `/docs/ADR.md` — ADR-008(롱폴링), ADR-009(HTML parse_mode)
- `/src/main/java/com/example/ainewsdigest/collect/NewsSource.java` — 포트 작성 패턴
- `/src/main/java/com/example/ainewsdigest/curation/OpenAiClient.java` — step 4의 HTTP 클라이언트 작성 패턴(재시도, 타임아웃)
- `/src/main/java/com/example/ainewsdigest/digest/DigestMessageBuilder.java` — 여기서 만든 HTML을 보낸다

## 작업

텔레그램 Bot API 어댑터를 만든다. 발송과 업데이트 수신 둘 다 같은 HTTP 클라이언트를 쓰므로 이 step에서 함께 만든다.

### 포트 2개 (`delivery` 패키지)

```java
// delivery/Messenger.java
public interface Messenger {
    SendResult send(long chatId, String html);
}

// delivery/SendResult.java  — sealed interface 또는 record + enum
public sealed interface SendResult {
    record Success(long messageId) implements SendResult {}
    /** 403 — 사용자가 봇을 차단했거나 대화를 삭제했다. 구독자를 비활성화해야 한다. */
    record Blocked(String description) implements SendResult {}
    /** 429 — retryAfterSeconds만큼 기다린 뒤 재시도해야 한다. */
    record RateLimited(int retryAfterSeconds) implements SendResult {}
    /** 그 외 실패. retryable이면 백오프 후 재시도 가능. */
    record Failed(String errorCode, String description, boolean retryable) implements SendResult {}
}

// delivery/UpdateSource.java
public interface UpdateSource {
    /** 롱폴링. offset 이상의 업데이트를 timeoutSeconds까지 기다렸다 반환한다. 예외를 던지지 않는다. */
    PollResult getUpdates(long offset, int timeoutSeconds);
}

// delivery/PollResult.java
public sealed interface PollResult {
    record Updates(List<TelegramUpdate> updates) implements PollResult {}
    record Failure(String reason) implements PollResult {}
}

// delivery/TelegramUpdate.java
public record TelegramUpdate(long updateId, long chatId, String text) {}
```

**`getUpdates`의 실패를 빈 리스트로 표현하지 마라 (ADR-016).** 롱폴링에서는 "타임아웃까지 기다렸는데 업데이트가 없었다"가 가장 흔한 **정상** 응답이고 그것도 빈 리스트다. 둘을 같은 값으로 만들면 step 8의 폴러가 실패를 감지할 수 없어 백오프를 걸 방법이 아예 없어진다. 결국 정상 무응답마다 5초씩 자거나, 네트워크가 끊긴 채로 초당 수십 번 재시도하게 된다.

### 구현

`com.example.ainewsdigest.delivery.TelegramClient implements Messenger, UpdateSource`

**sendMessage**

```
POST {baseUrl}/bot{token}/sendMessage
{
  "chat_id": ...,
  "text": "...",
  "parse_mode": "HTML",
  "link_preview_options": { "is_disabled": true }
}
```

- `link_preview_options.is_disabled`를 반드시 켠다. 끄면 첫 URL의 미리보기 카드가 붙어 메시지가 지저분해진다
- 응답이 `{"ok":true,"result":{"message_id":N}}` 이면 `Success`
- HTTP 403 → `Blocked`
- HTTP 429 → 응답 본문 `parameters.retry_after` 를 읽어 `RateLimited`
- HTTP 400 → `Failed(retryable=false)`. **재시도하지 마라.** 요청 자체가 잘못된 것이라 다시 보내도 같은 결과다
- HTTP 5xx / 타임아웃 / 연결 실패 → `Failed(retryable=true)`
- **어떤 경우에도 예외를 밖으로 던지지 마라.** 전부 `SendResult`로 표현한다. 이유: 구독자 수백 명을 순회하는 발송 루프에서 예외가 튀면 나머지 구독자 발송이 중단된다

**getUpdates**

```
GET {baseUrl}/bot{token}/getUpdates?offset={offset}&timeout={timeoutSeconds}&allowed_updates=["message"]
```

- 롱폴링이므로 HTTP read timeout은 `timeoutSeconds`보다 넉넉히 크게 잡는다 (예: +10초).
  이유: 읽기 타임아웃이 폴링 타임아웃보다 짧으면 매번 예외가 나고 폴링이 동작하지 않는다
- `message.text`가 없는 업데이트(사진, 스티커 등)는 건너뛴다
- 실패 시 `PollResult.Failure`를 반환한다 (예외를 던지지 않는다)

**타임아웃 구성 (ADR-015).** 전역 `spring.http.client.read-timeout`은 수집용 10초라서 그대로 쓰면 30초 롱폴링이 매 사이클 터진다. 발송과 폴링은 요구 타임아웃이 다르므로 **`RestClient`를 두 개 만든다**:

```java
// 발송용: read timeout 10s
// 폴링용: read timeout = poll-timeout + 10s (예: 40s)
this.pollingClient = builder.baseUrl(props.baseUrl())
        .requestFactory(factoryBuilder.build(
                defaults.withReadTimeout(props.pollTimeout().plusSeconds(10))))
        .build();
```

### 설정

```yaml
ainewsdigest:
  telegram:
    base-url: https://api.telegram.org
    bot-token: ${TELEGRAM_BOT_TOKEN:}
    admin-chat-id: ${ADMIN_CHAT_ID:}
    poll-timeout: 30s
```

봇 토큰은 환경변수로만 주입한다.

### 로그에 봇 토큰을 남기지 마라 — 예외 메시지가 특히 위험하다

텔레그램은 토큰을 **URL 경로**에 넣는다(`/bot{token}/sendMessage`). 그래서 URL을 직접 로깅하지 않아도 새어 나가는 경로가 있다.

**Spring의 `ResourceAccessException` 메시지에는 요청 URL이 통째로 들어 있다:**

```
I/O error on GET request for "https://api.telegram.org/bot8123456:AAH...실제토큰.../getUpdates": Read timed out
```

롱폴링은 타임아웃과 연결 오류가 일상이다. `log.warn("polling failed", e)` 한 줄이면 토큰이 로그 파일에 **반복해서** 평문으로 쌓인다. 토큰이 유출되면 봇을 완전히 탈취당한다 — 구독자 전원에게 임의의 메시지를 보낼 수 있다.

- `TelegramClient`에 마스킹 유틸을 두고 **모든 로깅 경로가 이를 통과**하게 한다: `/bot<숫자>:<영숫자>` → `/bot***`
- **예외 객체를 로거에 그대로 넘기지 마라.** `log.warn("...", e)`는 스택트레이스와 메시지를 전부 찍는다. `log.warn("... : {}", mask(e.getMessage()))` 형태로 마스킹된 메시지만 남긴다
- `SendResult.Failed`·`PollResult.Failure`의 `description`/`reason`에 담는 문자열도 마스킹한다. 이 값들은 step 9·11을 거쳐 **관리자 알림 메시지로 텔레그램에 발송된다**

## 테스트

`src/test/java/com/example/ainewsdigest/delivery/TelegramClientTest.java` — **WireMock 사용.**

1. 200 + `ok:true` → `Success`, `messageId` 매핑
2. 요청 바디에 `"parse_mode":"HTML"` 과 링크 프리뷰 비활성이 들어간다
3. 403 → `Blocked`
4. 429 + `parameters.retry_after: 7` → `RateLimited(7)`
5. 400 → `Failed(retryable=false)`
6. 500 → `Failed(retryable=true)`
7. 연결 타임아웃 → `Failed(retryable=true)`, 예외가 밖으로 나오지 않는다
8. `getUpdates`가 `PollResult.Updates`를 반환하고 `chatId`·`text`가 채워진다
9. `getUpdates` 응답에 `text` 없는 메시지가 섞여 있으면 그것만 제외한다
10. `getUpdates`가 500을 받으면 `PollResult.Failure`를 반환한다 (빈 `Updates`가 아니다)
11. **업데이트가 0건인 정상 응답은 `Updates(빈 리스트)`다** — 10번의 실패와 구분되어야 한다
12. 폴링용 클라이언트의 read timeout이 `poll-timeout`보다 크다 (WireMock 지연 응답으로 검증)
13. **타임아웃을 유발했을 때 캡처한 로그 출력에 토큰 문자열이 없다.** 알아보기 쉬운 더미 토큰(`8123456:AAHdummyTokenValue`)을 설정하고, `ListAppender`로 로그를 캡처해 그 문자열이 포함되지 않는지 단언한다
14. **`PollResult.Failure.reason`과 `SendResult.Failed.description`에도 토큰이 없다** (관리자 알림으로 발송되는 값이다)

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
3. 결과에 따라 `phases/0-mvp/index.json`의 step 7을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 테스트에서 실제 `api.telegram.org`를 호출하지 마라. 이유: 봇 토큰이 필요하고 CI가 외부에 종속된다. WireMock을 쓴다
- 봇 토큰을 코드나 `application.yml`에 하드코딩하지 마라. 이유: 토큰이 유출되면 누구나 봇을 조종할 수 있다
- `log.warn("...", e)`처럼 예외 객체를 로거에 그대로 넘기지 마라. 이유: Spring의 I/O 예외 메시지에는 토큰이 든 요청 URL이 통째로 들어 있다. 롱폴링은 타임아웃이 일상이라 그 한 줄이 매 사이클 토큰을 로그에 쌓는다
- `bot-token`이 비어 있다고 이 step을 `blocked`로 만들지 마라. 이유: 모든 테스트가 WireMock을 쓰므로 토큰 없이 빌드가 통과해야 한다
- 웹훅(`setWebhook`)을 구현하지 마라. 이유: ADR-008에서 롱폴링으로 결정했다
- 여기서 폴링 루프를 돌리지 마라(`@Scheduled`, 백그라운드 스레드 금지). 이유: step 8의 범위다. 이 클래스는 `getUpdates`를 **한 번** 호출하는 것까지만 한다
- 구독자 등록·해지 로직을 만들지 마라. 이유: step 8의 범위다
- `SendResult` 대신 예외로 오류를 표현하지 마라. 이유: 위에 설명했다. 한 명의 실패가 전체 발송을 중단시킨다
- `getUpdates`의 실패를 빈 리스트로 표현하지 마라. 이유: 롱폴링의 정상 무응답과 구분이 안 되어 step 8이 백오프를 구현할 수 없다 (ADR-016)
- 발송용과 폴링용이 같은 `RestClient`를 쓰게 하지 마라. 이유: 필요한 read timeout이 10s와 40s로 다르다. 하나로 합치면 둘 중 하나가 깨진다
- 기존 테스트를 깨뜨리지 마라
