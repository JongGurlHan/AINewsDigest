# Step 10: web-archive

## 읽어야 할 파일

- `/docs/UI_GUIDE.md` — **전문을 읽어라. 색상 변수, 안티패턴, 레이아웃, 타이포그래피가 전부 확정되어 있다**
- `/docs/PRD.md` — 디자인 방향, 핵심 기능
- `/docs/ADR.md` — ADR-004(랜딩+아카이브를 만드는 이유), ADR-003(딥링크)
- `/docs/ARCHITECTURE.md` — 웹 조회 데이터 흐름
- `/src/main/java/com/example/ainewsdigest/digest/` — `Digest`, `DigestItem`, `DigestRepository`

## 작업

Thymeleaf 서버 사이드 렌더링으로 3개 화면을 만든다.

### 컨트롤러

`com.example.ainewsdigest.digest.DigestController`

| 경로 | 화면 | 내용 |
|---|---|---|
| `GET /` | 랜딩 | 한 줄 소개 + 구독 딥링크 버튼 + **최근 발송분 5일치를 바로 노출** |
| `GET /archive` | 목록 | 발송 날짜별 목록, 페이징 (20개씩) |
| `GET /archive/{date}` | 상세 | 해당 날짜 다이제스트 전체. `yyyy-MM-dd` 형식 |

- **`sentAt`이 채워진 것만 보여준다.** 미발송분은 아직 구독자에게 나가지 않았으므로 노출하면 안 된다
- `EMPTY`인 날도 **발송됐다면 목록에 노출하고** "뉴스 없음"으로 표시한다. `DigestView.empty = (status == EMPTY)`
- **`status == SENT`로 필터링하지 마라.** EMPTY는 발송돼도 status가 EMPTY로 남으므로 그 날짜가 아카이브에서 통째로 사라진다 (ARCHITECTURE.md "다이제스트 상태 규칙")
- 존재하지 않는 날짜는 404를 반환한다
- 잘못된 날짜 형식은 400을 반환한다

### DTO — Entity를 그대로 넘기지 마라 (CLAUDE.md CRITICAL)

```java
// digest/dto/DigestView.java
public record DigestView(LocalDate date, String displayDate, boolean empty, List<DigestItemView> items) {}

// digest/dto/DigestItemView.java
public record DigestItemView(int position, String titleKo, String summaryKo,
                             String sourceUrl, String sourceDomain) {}
```

`displayDate`는 `2026년 8월 5일 (수)` 형태의 한글 표기로 서비스 계층에서 만든다. 템플릿에서 포맷팅 로직을 돌리지 마라.

### 서비스

`com.example.ainewsdigest.digest.DigestQueryService` — **step 8에서 이미 만들어져 있다.** 새로 만들지 말고 화면용 메서드를 추가한다 (목록 페이징, 날짜 상세, 랜딩용 최근 5일). `findLatestSentWithContent()`는 봇의 `/start` 응답이 쓰고 있으므로 시그니처를 바꾸지 마라. 조회 전용이므로 모든 메서드에 `@Transactional(readOnly = true)`.

`DigestView`/`DigestItemView`도 step 8에서 만들어져 있다. 필드가 모자라면 추가하되, 기존 필드를 바꾸면 step 8의 사용처를 함께 고친다.

`DigestItem`을 함께 조회할 때 **N+1 쿼리가 나지 않게** `fetch join` 또는 `@EntityGraph`를 쓴다. 목록 화면에서 20개 다이제스트를 렌더링하며 매번 항목을 따로 조회하면 안 된다.

### 템플릿

```
templates/
├── fragments/layout.html    # 공통 head, 헤더, 푸터
├── digest/landing.html
├── digest/archive.html
└── digest/detail.html
static/css/main.css
```

- 공통 조각은 `th:replace`로 조합한다 (UI_GUIDE 규약)
- 페이지 템플릿은 `templates/{도메인}/{화면}.html` 배치
- **인라인 `style` 속성 금지.** 모든 스타일은 `main.css`의 클래스로
- **텍스트 출력은 전부 `th:text`를 쓴다. `th:utext`를 쓰지 마라.** 화면에 뿌리는 제목·요약은 LLM이 만든 문자열이라 무엇이 들어올지 보장할 수 없다. `th:text`가 이스케이프해주는 것이 유일한 방어선이다
- **JavaScript를 쓰지 마라** (UI_GUIDE 규약)
- 구독 버튼은 `https://t.me/{botUsername}?start=web` 으로 링크한다. `botUsername`은 설정값으로 주입한다

```yaml
ainewsdigest:
  telegram:
    bot-username: ${TELEGRAM_BOT_USERNAME:ainewsdigest_bot}
```

### CSS

`UI_GUIDE.md`의 색상 표를 그대로 CSS 커스텀 프로퍼티로 정의하고, 개별 요소에 raw hex를 쓰지 마라. 레이아웃(`max-width: 720px`, 좌측 정렬), 타이포그래피 표, 애니메이션 제한(색상 전환 0.15s만)을 그대로 따른다.

**UI_GUIDE의 "AI 슬롭 안티패턴" 표에 있는 것을 하나도 쓰지 마라.** 특히 보라/인디고 색상, glassmorphism, gradient text, 네온 글로우, 배경 orb.

## 테스트

`src/test/java/com/example/ainewsdigest/digest/DigestControllerTest.java` — `@WebMvcTest` + 서비스는 `@MockitoBean`

1. `GET /` 가 200이고 최근 다이제스트 제목이 응답 본문에 포함된다
2. `GET /` 응답에 구독 딥링크(`t.me/...?start=web`)가 들어 있다
3. `GET /archive` 가 200이고 날짜 목록이 렌더링된다
4. `GET /archive/2026-08-05` 가 200이고 항목들이 렌더링된다
5. 없는 날짜는 404
6. `GET /archive/2026-13-99` 같은 잘못된 형식은 400
7. 미발송(`sentAt == null`) 다이제스트는 어느 화면에도 노출되지 않는다

`DigestQueryServiceTest` — Testcontainers + `@SpringBootTest`

8. `sentAt`이 채워진 것만 조회된다
9. **발송된 `EMPTY` 다이제스트가 목록에 나오고 `DigestView.empty == true`다** (status로 걸러 사라지지 않는다)
10. 목록 조회 시 항목까지 한 번에 가져온다 (N+1 미발생 — 쿼리 카운트 또는 fetch join 검증)

## Acceptance Criteria

```bash
./gradlew build   # Git Bash 기준. PowerShell/cmd에서는 gradlew.bat build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다. (Docker Desktop이 실행 중이어야 한다)
2. 아키텍처 체크리스트를 확인한다:
   - ARCHITECTURE.md 디렉토리 구조를 따르는가?
   - **UI_GUIDE.md의 안티패턴 표를 위반하지 않았는가?**
   - CLAUDE.md CRITICAL 규칙을 위반하지 않았는가? (Entity를 View에 직접 넘기지 않았는가)
3. 결과에 따라 `phases/0-mvp/index.json`의 step 10을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `Digest`/`DigestItem` 엔티티를 뷰 모델로 직접 넘기지 마라. 이유: CLAUDE.md CRITICAL 규칙이다. 반드시 DTO로 변환한다
- 컨트롤러에서 리포지토리를 직접 호출하지 마라. 이유: CLAUDE.md CRITICAL 규칙이다. 서비스를 거친다
- JavaScript 파일을 만들거나 `<script>`를 넣지 마라. 이유: UI_GUIDE 규약이며, 이 사이트에 동적 동작이 필요한 화면이 없다
- 웹폰트를 로드하지 마라(Google Fonts 등). 이유: UI_GUIDE에 명시. 로딩 지연과 CLS를 만든다
- CSS 프레임워크(Tailwind, Bootstrap 등)를 추가하지 마라. 이유: 순수 CSS로 작성하기로 했다
- 관리자 페이지나 Spring Security를 추가하지 마라. 이유: PRD MVP 제외 사항이다
- RSS 출력(`/feed.xml`)을 만들지 마라. 이유: PRD MVP 제외 사항이다
- 미발송(`sentAt == null`) 다이제스트를 노출하지 마라. 이유: 아직 구독자에게 발송되지 않은 내용이 웹에 먼저 뜬다
- **`th:utext`를 쓰지 마라.** 이유: 화면 내용이 전부 LLM 생성물이다. 이스케이프를 끄는 순간 XSS가 열린다
- **`digest.message_text`를 화면에 렌더링하지 마라.** 이유: 그건 텔레그램용으로 조립된 HTML 덩어리다. 그대로 뿌리려면 `th:utext`가 필요하고 위 항목을 어기게 된다. 화면은 `digest_item`으로만 만든다
- `DigestQueryService`를 새로 만들지 마라. 이유: step 8이 이미 만들었다. 같은 조회가 두 벌 생기면 봇과 웹이 서로 다른 최신호를 보여준다
- 기존 테스트를 깨뜨리지 마라
