# Step 5: message-builder

## 읽어야 할 파일

- `/docs/PRD.md` — 발송 규칙(건별 포맷, 4,000자 제한, 뉴스 없음 메시지)
- `/docs/ADR.md` — **ADR-009(HTML parse_mode, 도메인 하이퍼링크) — 이 step의 근거**
- `/src/main/java/com/example/ainewsdigest/curation/SummarizedArticle.java` — step 4 산출물
- `/src/main/java/com/example/ainewsdigest/collect/CandidateArticle.java` — `sourceDomain` 필드

## 작업

요약된 기사 목록을 텔레그램에 보낼 HTML 메시지 한 덩어리로 조립한다. **이 클래스는 외부 의존이 없는 순수 로직이며, 이 단계의 버그는 매일 아침 발송 실패로 직결된다.**

### 파일

`com.example.ainewsdigest.digest.DigestMessageBuilder` (`@Component`)

```java
public record DigestMessage(String html, int visibleLength, int includedCount) {}

/** 요약 결과를 텔레그램 HTML 메시지로 만든다. articles가 비면 '뉴스 없음' 메시지를 만든다. */
public DigestMessage build(LocalDate date, List<SummarizedArticle> articles);
```

### 메시지 형식

```
<b>오늘의 AI 뉴스</b> · 2026년 8월 5일

<b>1. {titleKo}</b>
{summaryKo}
<a href="{sourceUrl}">{sourceDomain}</a>

<b>2. {titleKo}</b>
...
```

- 항목은 `score` 내림차순, 즉 입력 순서를 그대로 따른다
- 기사가 없으면 본문을 `오늘의 AI 뉴스는 없습니다.` 로 한다 (PRD 명시 문구)

### HTML 이스케이프 — 반드시 지킬 것

텔레그램 HTML 모드에서 특수문자로 취급되는 것은 `&`, `<`, `>` **셋뿐**이다.

- `titleKo`, `summaryKo`, `sourceDomain`은 **반드시 이스케이프한다**: `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;`
- 순서가 중요하다. `&`를 먼저 치환하지 않으면 `&lt;`가 다시 `&amp;lt;`가 된다
- `href` 속성 안의 URL도 `&` → `&amp;`로 이스케이프한다 (쿼리 파라미터에 `&`가 흔하다)
- **MarkdownV2 이스케이프를 하지 마라.** 백슬래시를 넣으면 HTML 모드에서 그대로 출력된다

### 길이 계산 — 가장 중요한 규칙

텔레그램의 4,096자 제한은 **"after entities parsing"** 기준이다. 즉 화면에 보이는 텍스트 길이만 센다.

따라서 `visibleLength`는 아래처럼 계산한다:

- `<b>`, `</b>`, `<a href="...">`, `</a>` 같은 **태그는 0자로 센다**
- `<a href="https://아주-긴-url">cursor.com</a>` 은 **`cursor.com` 10자로만 센다.** href의 URL은 세지 않는다
- `&amp;`, `&lt;`, `&gt;` 는 **각각 1자로 센다** (파싱 후 `&`, `<`, `>` 한 글자가 되므로)
- 줄바꿈은 1자

원시 문자열 `html.length()`를 그대로 쓰면 안 된다. 그러면 URL 때문에 실제보다 훨씬 크게 나와서 멀쩡한 기사가 잘려 나간다.

### 길이 초과 처리

상한은 **4,000자**(텔레그램 한도 4,096보다 여유를 둔 값).

1. 전체를 조립해 `visibleLength`를 잰다
2. 4,000자를 넘으면 **가장 마지막 항목(= 점수가 가장 낮은 항목)을 제거하고 다시 조립한다**
3. 1건이 남을 때까지 반복한다
4. 1건만 남았는데도 넘으면, 그 항목의 `summaryKo`를 뒤에서 잘라 맞춘다 (최후 수단)

번호는 항목이 제거된 뒤 **다시 매긴다**. 3건이 남으면 1, 2, 3이어야 한다.

### 설정

```yaml
ainewsdigest:
  message:
    max-visible-length: 4000
    empty-text: "오늘의 AI 뉴스는 없습니다."
```

## 테스트

`src/test/java/com/example/ainewsdigest/digest/DigestMessageBuilderTest.java` — **순수 단위 테스트. 스프링 컨텍스트도 DB도 띄우지 않는다.**

1. 3건 입력 시 번호가 1, 2, 3으로 매겨지고 각 항목에 제목·요약·도메인 링크가 들어간다
2. 빈 리스트 입력 시 `오늘의 AI 뉴스는 없습니다.` 가 나오고 `includedCount == 0`
3. 제목에 `&`, `<`, `>`가 있으면 `&amp;`, `&lt;`, `&gt;`로 이스케이프된다
4. 요약에 `<script>`가 있어도 이스케이프되어 태그로 해석되지 않는다
5. `href` 안의 `&`가 `&amp;`로 이스케이프된다
6. **길이 계산이 href URL을 세지 않는다**: 매우 긴 URL(500자)을 가진 항목이 있어도 `visibleLength`가 그만큼 늘지 않는다
7. `&amp;`가 길이 계산에서 1자로 센다
8. **5건 × 600자로 4,000자를 넘으면 마지막 항목이 제거되고 4건이 된다** (`includedCount == 4`)
9. 항목 제거 후 번호가 1~4로 다시 매겨진다
10. 1건인데 요약이 5,000자면 잘려서 4,000자 이하가 된다
11. 어떤 입력에서도 결과의 `visibleLength <= 4000`

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
3. 결과에 따라 `phases/0-mvp/index.json`의 step 5를 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `parse_mode`를 MarkdownV2나 Markdown으로 바꾸지 마라. 이유: ADR-009에 근거가 있다. 한글 요약에는 마침표가 반드시 들어가고, MarkdownV2에서 마침표는 이스케이프 대상이라 하나만 놓쳐도 발송 전체가 400으로 실패한다
- `<b>`, `<i>`, `<a>`, `<code>`, `<pre>` 외의 HTML 태그를 쓰지 마라. 이유: 텔레그램이 지원하지 않는 태그가 있으면 400으로 실패한다. `<br>`도 지원하지 않으니 줄바꿈은 `\n`을 쓴다
- 메시지를 여러 통으로 분할하지 마라. 이유: 한 통에 담기로 결정했다. 넘치면 건수를 줄인다
- 텔레그램 API를 호출하지 마라. 이유: step 7의 범위다. 이 클래스는 문자열만 만든다
- DB에 접근하지 마라. 이유: 순수 로직이어야 테스트가 빠르고 확실하다
- 기존 테스트를 깨뜨리지 마라
