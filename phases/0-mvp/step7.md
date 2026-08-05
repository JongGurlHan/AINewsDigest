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
    /** 롱폴링. offset 이상의 업데이트를 timeoutSeconds까지 기다렸다 반환한다. */
    List<TelegramUpdate> getUpdates(long offset, int timeoutSeconds);
}

// delivery/TelegramUpdate.java
public record TelegramUpdate(long updateId, long chatId, String text) {}
```

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
- 실패 시 빈 리스트를 반환한다 (예외를 던지지 않는다)

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

**로그에 봇 토큰을 남기지 마라.** URL 경로에 토큰이 들어가므로, 요청 URL을 그대로 로깅하면 토큰이 로그 파일에 평문으로 쌓인다. 로깅할 때는 토큰 부분을 마스킹한다.

## 테스트

`src/test/java/com/example/ainewsdigest/delivery/TelegramClientTest.java` — **WireMock 사용.**

1. 200 + `ok:true` → `Success`, `messageId` 매핑
2. 요청 바디에 `"parse_mode":"HTML"` 과 링크 프리뷰 비활성이 들어간다
3. 403 → `Blocked`
4. 429 + `parameters.retry_after: 7` → `RateLimited(7)`
5. 400 → `Failed(retryable=false)`
6. 500 → `Failed(retryable=true)`
7. 연결 타임아웃 → `Failed(retryable=true)`, 예외가 밖으로 나오지 않는다
8. `getUpdates`가 업데이트 목록을 파싱하고 `chatId`·`text`를 채운다
9. `getUpdates` 응답에 `text` 없는 메시지가 섞여 있으면 그것만 제외한다
10. `getUpdates`가 500을 받으면 빈 리스트를 반환한다

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
- `bot-token`이 비어 있다고 이 step을 `blocked`로 만들지 마라. 이유: 모든 테스트가 WireMock을 쓰므로 토큰 없이 빌드가 통과해야 한다
- 웹훅(`setWebhook`)을 구현하지 마라. 이유: ADR-008에서 롱폴링으로 결정했다
- 여기서 폴링 루프를 돌리지 마라(`@Scheduled`, 백그라운드 스레드 금지). 이유: step 8의 범위다. 이 클래스는 `getUpdates`를 **한 번** 호출하는 것까지만 한다
- 구독자 등록·해지 로직을 만들지 마라. 이유: step 8의 범위다
- `SendResult` 대신 예외로 오류를 표현하지 마라. 이유: 위에 설명했다. 한 명의 실패가 전체 발송을 중단시킨다
- 기존 테스트를 깨뜨리지 마라
