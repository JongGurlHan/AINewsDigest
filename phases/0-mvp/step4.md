# Step 4: curation-openai

## 읽어야 할 파일

- `/docs/PRD.md` — 관심 주제 우선순위, 발송 규칙(건수·글자수)
- `/docs/ADR.md` — ADR-002(OpenAI API 직접호출), ADR-007(2단계 LLM), ADR-013(점수 임계값)
- `/src/main/java/com/example/ainewsdigest/collect/CandidateArticle.java` — step 2 산출물
- `/src/main/java/com/example/ainewsdigest/collect/ArticleContentExtractor.java` — step 3 산출물
- `/src/main/java/com/example/ainewsdigest/collect/NewsSource.java` — 포트 작성 패턴

## 작업

LLM으로 후보를 채점·선별하고, 본문이 확보된 기사를 한글로 요약한다. 호출은 2단계로 나뉜다(ADR-007).

### 값 객체

```java
// curation/ScoredArticle.java
public record ScoredArticle(CandidateArticle article, int score, String reason) {}

// curation/ArticleWithContent.java
public record ArticleWithContent(CandidateArticle article, int score, String content) {}

// curation/SummarizedArticle.java
public record SummarizedArticle(
        CandidateArticle article,
        int score,
        String titleKo,     // 한글 제목
        String summaryKo    // 한글 요약 3~4문장
) {}
```

### 포트 2개

```java
// curation/ArticleSelector.java
public interface ArticleSelector {
    /**
     * 후보를 1~5점으로 채점하고 점수 내림차순으로 반환한다.
     * recentTitles: 최근 7일간 발송한 한글 제목. 같은 사건의 재발송을 막는 데 쓴다.
     * 실패 시 예외를 던진다 (호출자가 재시도/실패 처리를 결정한다).
     */
    List<ScoredArticle> scoreAndRank(List<CandidateArticle> candidates, List<String> recentTitles);
}

// curation/ArticleSummarizer.java
public interface ArticleSummarizer {
    List<SummarizedArticle> summarize(List<ArticleWithContent> articles);
}
```

### 저수준 클라이언트

```java
// curation/OpenAiClient.java
/** Chat Completions 호출. JSON Schema로 응답 구조를 강제한다. */
public String completeAsJson(String systemPrompt, String userPrompt, Map<String, Object> jsonSchema);
```

- 엔드포인트: `{baseUrl}/chat/completions`, 헤더 `Authorization: Bearer {apiKey}`
- `response_format`에 `{"type": "json_schema", "json_schema": {...,"strict": true}}`를 넣어 구조를 강제한다
- 4xx는 재시도하지 않는다(요청 자체가 잘못된 것). 429와 5xx는 지수 백오프로 최대 3회 재시도한다
- 최종 실패 시 예외를 던진다

### 채점 기준 — 프롬프트에 명시할 것

우선순위와 점수 기준을 시스템 프롬프트에 박아 넣는다.

- 관심 주제 우선순위: ① AI 코딩 도구(Claude Code, Codex, Cursor 등) ② 모델 릴리즈/API ③ AI 산업·트렌드
- 점수 기준: **5점** = 내가 매일 쓰는 도구의 동작이 실제로 바뀜 / **4점** = 새 모델·API 출시 등 곧 영향 / **3점** = 알아두면 유용 / **2점** = 단순 동향 / **1점** = 개발자와 무관하거나 홍보성
- `recentTitles`에 있는 것과 **같은 사건**을 다루면 1점을 준다
- 응답은 `{"results":[{"index":0,"score":4,"reason":"..."}]}` 형태. `index`는 입력 배열의 위치

### 요약 규칙 — 프롬프트에 명시할 것

- 반드시 한글로 쓴다. 영문 원문은 번역한다
- 제목은 한글 40자 이내
- 요약은 3~4문장, **한글 600자 이내**
- **본문에 없는 내용을 쓰지 마라. 추측·배경지식 보충 금지**
- 고유명사(제품명, 회사명, 버전)는 원문 표기를 유지한다
- 응답은 `{"results":[{"index":0,"titleKo":"...","summaryKo":"..."}]}` 형태

### 설정

```yaml
ainewsdigest:
  curation:
    openai:
      base-url: https://api.openai.com/v1
      api-key: ${OPENAI_API_KEY:}
      model: gpt-5-mini
      timeout: 60s
      max-retries: 3
    min-score: 3          # 이 점수 미만은 채택하지 않는다 (ADR-013)
    select-count: 8       # 크롤링 실패를 감안해 필요 건수보다 넉넉히 고른다
    max-items: 5
    min-items: 1
```

`api-key`는 환경변수로만 주입한다. 기본값을 실제 키로 채워 커밋하지 마라.

## 테스트

`src/test/java/com/example/ainewsdigest/curation/` 하위. **WireMock으로 OpenAI 응답을 스텁한다.**

1. `OpenAiArticleSelector`가 응답 JSON을 `ScoredArticle`로 매핑하고 점수 내림차순 정렬한다
2. 요청 바디에 `response_format.type == "json_schema"`가 들어간다
3. 요청 바디에 `recentTitles`가 포함된다
4. 429 응답 후 200이면 재시도해서 성공한다
5. 400 응답이면 **재시도하지 않고** 즉시 예외를 던진다 (재시도 횟수를 WireMock으로 검증)
6. 5xx가 계속되면 3회 시도 후 예외를 던진다
7. `OpenAiArticleSummarizer`가 응답을 `SummarizedArticle`로 매핑한다
8. 응답 `index`가 입력 순서와 다르게 와도 올바른 기사에 매칭된다
9. 응답 배열이 입력보다 적게 와도 예외 없이 온 것만 반환한다

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
3. 결과에 따라 `phases/0-mvp/index.json`의 step 4를 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 테스트에서 실제 OpenAI API를 호출하지 마라. 이유: 비용이 발생하고 CI가 API 키와 외부 상태에 종속된다. 반드시 WireMock을 쓴다
- API 키를 코드나 `application.yml`에 하드코딩하지 마라. 이유: 커밋되면 즉시 유출이다. 환경변수 `OPENAI_API_KEY`로만 주입한다
- `api-key`가 비어 있다고 해서 이 step을 `blocked`로 만들지 마라. 이유: 모든 테스트가 WireMock을 쓰므로 키 없이 빌드가 통과해야 한다
- 파이프라인 오케스트레이션(수집→선별→크롤링→요약 연결)을 만들지 마라. 이유: step 6의 범위다. 이 step은 선별기와 요약기 자체만 만든다
- 텔레그램 메시지 문자열을 조립하지 마라. 이유: step 5의 범위다
- DB에 저장하지 마라. 이유: step 6의 범위다
- 응답 파싱을 정규식으로 하지 마라. 이유: JSON Schema로 구조를 강제했으므로 Jackson으로 파싱한다
- 기존 테스트를 깨뜨리지 마라
