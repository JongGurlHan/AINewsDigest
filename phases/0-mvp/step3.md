# Step 3: article-extractor

## 읽어야 할 파일

- `/docs/ARCHITECTURE.md` — `collect` 패키지 역할
- `/docs/ADR.md` — **ADR-006(본문 추출) — 이 step의 존재 이유가 여기 있다**
- `/src/main/java/com/example/ainewsdigest/collect/CandidateArticle.java` — step 2 산출물
- `/src/main/java/com/example/ainewsdigest/collect/NewsSource.java` — 포트 작성 패턴 참고
- `/build.gradle.kts` — `org.jsoup:jsoup:1.21.1`

## 작업

선별된 기사의 원문 본문을 추출한다. HN은 제목과 URL만 주므로, 본문 없이 요약을 시키면 모델이 내용을 지어낸다(ADR-006). 본문 확보 실패는 정상적인 결과이며 해당 기사를 후보에서 탈락시킨다.

### 포트와 구현

```java
// collect/ArticleContentExtractor.java  — 아웃바운드 포트
public interface ArticleContentExtractor {
    /** 본문 추출에 성공하면 평문 텍스트, 실패하면 Optional.empty(). 예외를 던지지 않는다. */
    Optional<String> extract(String url);
}

// collect/JsoupArticleExtractor.java
```

### 설계상 반드시 지킬 것 — 네트워크와 파싱을 분리하라

파싱 로직을 `extract(String url)` 안에 통째로 넣지 말고, HTML 문자열을 받는 별도 메서드로 분리한다.

```java
/** 테스트가 네트워크 없이 파싱만 검증할 수 있도록 분리한다. */
String extractFromHtml(String html, String baseUri);
```

이유: 이 클래스의 진짜 로직은 본문 골라내기이고, 그건 HTTP 없이 검증돼야 한다. 네트워크가 낀 테스트는 느리고 불안정하다.

### 추출 휴리스틱

1. `script`, `style`, `nav`, `header`, `footer`, `aside`, `form`, `noscript` 요소를 먼저 제거한다
2. 본문 후보를 순서대로 찾는다: `article` → `main` → `[role=main]` → `.post-content, .article-body, .entry-content`
3. 위에서 못 찾으면, `<p>` 텍스트 총 길이가 가장 큰 블록 요소를 고른다
4. 선택된 요소 안의 `<p>` 텍스트를 줄바꿈으로 이어 붙인다
5. 연속 공백·빈 줄을 하나로 줄인다
6. 결과가 **200자 미만이면 추출 실패로 간주해 `Optional.empty()`를 반환한다**
   이유: 쿠키 배너나 페이월 안내문만 긁힌 경우다. 이런 텍스트로 요약하면 엉뚱한 내용이 발송된다
7. 성공 시 **앞 3,000자로 자른다**

### 네트워크 호출

```java
Jsoup.connect(url)
     .timeout(5000)              // 5초
     .userAgent(...)             // 설정값
     .followRedirects(true)
     .ignoreHttpErrors(false)
     .maxBodySize(2 * 1024 * 1024)
     .get();
```

- 타임아웃·4xx·5xx·비HTML 응답(`Content-Type`이 `text/html`이 아님)은 전부 `Optional.empty()`
- 예외를 밖으로 던지지 마라. 로그만 남기고 빈 값을 돌려준다

### 설정

```yaml
ainewsdigest:
  collect:
    extractor:
      timeout: 5s
      max-content-length: 3000
      min-content-length: 200
      user-agent: "AINewsDigest/1.0 (+https://github.com/JongGurlHan)"
```

## 테스트

`src/test/java/com/example/ainewsdigest/collect/JsoupArticleExtractorTest.java`

`src/test/resources/fixtures/` 에 HTML 픽스처를 두고 `extractFromHtml`을 직접 호출한다:

1. `article` 태그가 있는 문서에서 본문이 추출된다
2. `article`이 없고 `main`만 있는 문서에서 추출된다
3. 둘 다 없으면 `<p>`가 가장 많은 블록에서 추출된다
4. `script`/`nav`/`footer` 안의 텍스트가 결과에 **포함되지 않는다**
5. 본문이 3,000자를 넘으면 3,000자로 잘린다
6. 추출 결과가 200자 미만이면 실패로 처리된다 (쿠키 배너만 있는 문서 픽스처)

네트워크 경로는 WireMock으로 최소 검증한다:

7. 404 응답이면 `Optional.empty()`
8. `Content-Type: application/pdf` 응답이면 `Optional.empty()`

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
3. 결과에 따라 `phases/0-mvp/index.json`의 step 3을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 테스트에서 실제 뉴스 사이트를 크롤링하지 마라. 이유: 사이트가 바뀌면 빌드가 깨지고, CI가 외부에 종속된다. 픽스처와 WireMock을 쓴다
- 추출 실패 시 예외를 던지지 마라. 이유: 페이월·봇차단으로 실패하는 사이트는 반드시 나온다. 정상 흐름의 일부이므로 `Optional.empty()`로 처리하고 파이프라인이 다음 기사로 넘어가게 해야 한다
- 헤드리스 브라우저(Selenium, Playwright 등)를 도입하지 마라. 이유: 의존성과 메모리 사용량이 급증하고 무료 티어 서버에서 감당하기 어렵다. JS 렌더링이 필요한 사이트는 포기한다
- robots.txt를 무시하는 우회 로직이나 봇 차단 회피(임의 UA 로테이션, 프록시 등)를 넣지 마라. 이유: 차단된 사이트는 그냥 탈락시킨다
- LLM을 호출하지 마라. 이유: step 4의 범위다
- 기존 테스트를 깨뜨리지 마라
