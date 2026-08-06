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
// collect/SafeUrlPolicy.java          — SSRF 방어 (ADR-017). @Component, 상태 없음
```

### SSRF 방어 — 이 step에서 가장 중요한 부분

**크롤링 대상 URL은 우리가 고른 것이 아니다.** HN에 링크를 올려 points 30만 넘기면 누구나 우리 서버가 **임의의 주소를 GET 하게** 만들 수 있다. 노리는 곳은 셋이다.

- `169.254.169.254` — OCI 인스턴스 메타데이터. IMDSv2는 헤더를 요구하지만 v1이 켜진 인스턴스는 인증 없이 응답한다
- `127.0.0.1:8080` — 우리 앱 자신. 그 외 로컬에서만 열린 포트 전부
- VCN 내부 사설 IP — 같은 네트워크의 다른 호스트

그리고 여기서 끝이 아니다. **받아온 본문은 LLM 요약을 거쳐 공개 아카이브 페이지에 게시되고 구독자 전원에게 발송된다.** 읽기만 가능한 SSRF가 아니라 유출 경로까지 완성되어 있다.

```java
// collect/SafeUrlPolicy.java
/** 이 URL에 요청을 보내도 되는가. 네트워크로 나가기 직전마다(리다이렉트 홉 포함) 호출한다. */
public boolean isAllowed(String url);
```

판정 기준:

1. 스킴이 `http` 또는 `https`가 아니면 거부 — **`UrlNormalizer.isHttpUrl()`을 재사용한다.** 같은 판정을 두 벌 구현하면 한쪽만 고쳐질 때 step 2의 수집 관문과 여기가 서로 다른 것을 통과시킨다
2. 호스트를 `InetAddress.getAllByName(host)`로 해석하고, **돌아온 주소 중 하나라도** 아래에 해당하면 거부
   - `isLoopbackAddress()` (127.0.0.0/8, ::1)
   - `isLinkLocalAddress()` (169.254.0.0/16, fe80::/10) ← 메타데이터 주소가 여기 있다
   - `isSiteLocalAddress()` (10/8, 172.16/12, 192.168/16)
   - `isAnyLocalAddress()` (0.0.0.0, ::)
   - `isMulticastAddress()`
   - IPv6 unique-local (`fc00::/7` — `isSiteLocalAddress()`가 잡지 못한다. 첫 바이트를 직접 확인한다)
3. 이름 해석 자체가 실패하면 거부

**"하나라도"가 중요하다.** 공격자가 A 레코드를 여러 개 걸어 하나만 정상 주소로 만들어 두면, 첫 번째 주소만 보는 검사는 통과한다.

### 리다이렉트를 직접 따라간다

`followRedirects(true)`를 쓰면 **중간 홉을 검사할 방법이 없다.** 최초 URL만 검사하는 것은 검사하지 않는 것과 같다 — `https://정상사이트/r` 이 `http://169.254.169.254/` 로 302 하는 순간 전부 무너진다.

```java
Jsoup.connect(currentUrl)
     .timeout(5000)
     .userAgent(...)                 // 설정값
     .followRedirects(false)         // 직접 따라간다
     .ignoreHttpErrors(true)         // 3xx를 예외가 아니라 응답으로 받기 위해
     .maxBodySize(2 * 1024 * 1024)
     .execute();
```

- 최대 **3홉**. 넘으면 `Optional.empty()`
- 홉마다 `Location` 헤더를 현재 URL 기준으로 절대화한 뒤 **`SafeUrlPolicy.isAllowed()`를 다시 통과시킨다**
- `ignoreHttpErrors(true)`로 바꾸므로 **상태 코드를 직접 확인해야 한다.** 200이 아니고 3xx도 아니면 `Optional.empty()`
- 최종 응답의 `Content-Type`이 `text/html`이 아니면 `Optional.empty()`
- 정책 위반은 예외가 아니라 `Optional.empty()`다. 기존 계약(ADR-016)을 그대로 지킨다. 다만 **로그에는 남긴다** — 정상적인 크롤링 실패와 구분되어야 조사할 수 있다

**막지 못하는 것**: DNS rebinding(검사 통과 후 연결 시점에 다른 IP로 재해석되는 경우). 막으려면 해석된 IP로 직접 연결하고 Host 헤더와 TLS SNI를 손봐야 하는데 jsoup API로는 깔끔하지 않다. ADR-017에 알려진 한계로 기록되어 있다.

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

### 네트워크 호출 정리

- 타임아웃·4xx·5xx·비HTML 응답·정책 위반·리다이렉트 초과는 **전부** `Optional.empty()`
- 예외를 밖으로 던지지 마라. 로그만 남기고 빈 값을 돌려준다

### 설정

```yaml
ainewsdigest:
  collect:
    extractor:
      timeout: 5s
      max-content-length: 3000
      min-content-length: 200
      max-redirects: 3
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

`SafeUrlPolicyTest` — **네트워크 없이 판정만 검증한다** (`localhost`·IP 리터럴은 해석에 네트워크가 필요 없다):

9. `http://169.254.169.254/opc/v1/instance/` 가 거부된다 (link-local)
10. `http://127.0.0.1:8080/actuator` · `http://localhost:8080/` 이 거부된다 (loopback)
11. `http://10.0.0.5/` · `http://192.168.1.1/` · `http://172.16.0.1/` 이 거부된다 (site-local)
12. `file:///etc/passwd` · `javascript:alert(1)` · `data:text/html,x` 가 거부된다 (스킴)
13. `https://example.com/article` 는 허용된다 (정상 경로가 막히지 않는지 확인)

`JsoupArticleExtractor`의 SSRF 경로는 WireMock으로 검증한다:

14. **외부 URL이 `http://169.254.169.254/`로 302 하면 `Optional.empty()`이고, 리다이렉트 대상으로 요청이 나가지 않는다** (WireMock에 해당 요청이 도달하지 않음을 검증)
15. 정상 URL 간 302는 따라가서 본문을 추출한다 (1홉)
16. 리다이렉트가 4번 이어지면 `Optional.empty()` (`max-redirects: 3` 초과)
17. 정책 위반으로 탈락한 경우와 정상 크롤링 실패가 로그에서 구분된다

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
- `followRedirects(true)`로 되돌리지 마라. 이유: jsoup이 중간 홉을 보여주지 않아 검사할 방법이 없다. 최초 URL만 검사하는 것은 검사하지 않는 것과 같다 — 302 한 번으로 전부 우회된다
- `SafeUrlPolicy`를 최초 URL에만 적용하지 마라. 이유: 위와 같다. 네트워크로 나가는 **모든** 요청 직전에 통과시킨다
- `InetAddress.getByName()`(단수)을 쓰지 마라. 이유: A 레코드를 여러 개 걸어 하나만 정상으로 만들면 통과한다. `getAllByName()`으로 전부 검사한다
- 정책 위반을 예외로 던지지 마라. 이유: ADR-016의 기존 계약이 `Optional`이다. 예외로 바꾸면 step 6의 크롤링 루프가 기사 하나 때문에 멈춘다
- 사설망 허용 설정(allowlist)을 만들지 마라. 이유: 이 서비스가 크롤링할 내부 소스는 없다. 예외 구멍을 만들면 그게 곧 우회 경로다
- LLM을 호출하지 마라. 이유: step 4의 범위다
- 기존 테스트를 깨뜨리지 마라
