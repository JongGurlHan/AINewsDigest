# Step 2: collect-sources

## 읽어야 할 파일

- `/docs/ARCHITECTURE.md` — `collect` 패키지 역할, 트랜잭션 경계(외부 호출은 트랜잭션 밖)
- `/docs/ADR.md` — ADR-005(수집 소스 선택 근거)
- `/src/main/java/com/example/ainewsdigest/collect/UrlNormalizer.java` — step 1 산출물. 여기서 사용한다
- `/build.gradle.kts` — `com.rometools:rome`(RSS/Atom), `org.wiremock:wiremock-standalone`(테스트)

## 작업

외부 소스에서 지난 24시간의 후보 기사를 모으는 어댑터들을 만든다.

### 값 객체와 포트

```java
// collect/CandidateArticle.java  (record)
public record CandidateArticle(
        String title,          // 원문 제목 (대개 영문)
        String url,            // 원문 URL
        String normalizedUrl,  // UrlNormalizer.normalize(url)
        String sourceDomain,   // UrlNormalizer.extractDomain(url)
        String sourceName,     // "Hacker News", "OpenAI", "Claude Code" 등
        int points,            // 인기 지표. 지표가 없는 소스는 0
        Instant publishedAt
) {
    /**
     * 어댑터는 반드시 이 팩토리로만 생성한다. 정규화 필드를 손으로 채우지 마라.
     * 이유: 어댑터 3개가 각자 정규화하면 하나만 빠뜨려도 조용히 깨진다. 그것도 나쁜 쪽으로 —
     * normalizedUrl이 빈 문자열이면 서로 다른 기사가 전부 같은 값이 되어 중복 제거에 몰살당하고,
     * 매일 아침 EMPTY 다이제스트가 나간다. 원인을 찾기 매우 어렵다.
     */
    public static CandidateArticle of(String title, String url, String sourceName,
                                      int points, Instant publishedAt, UrlNormalizer normalizer) { ... }
}

// collect/FetchResult.java  (record)
/** failed: 이 소스에서 하나라도 실패가 있었으면 true (RSS는 피드 하나만 죽어도 true). */
public record FetchResult(List<CandidateArticle> articles, boolean failed) {
    public static FetchResult of(List<CandidateArticle> articles) { ... }
    public static FetchResult failure() { ... }          // 빈 리스트 + failed=true
    public static FetchResult partial(List<CandidateArticle> articles) { ... }
}

// collect/NewsSource.java  — 아웃바운드 포트 (CLAUDE.md CRITICAL: 인터페이스 뒤에 둘 것)
public interface NewsSource {
    String name();
    /** since 이후 게시된 후보를 반환한다. 예외를 던지지 않는다. 실패는 FetchResult.failed로 표현한다. */
    FetchResult fetch(Instant since);
}
```

**실패를 빈 리스트로 표현하지 마라 (ADR-016).** 소스 3개가 전부 죽어도 후보 0건이 되는데, 빈 리스트만 돌려주면 step 6이 이걸 "오늘은 뉴스가 없는 날"로 판정해 EMPTY를 정상 발송하고 헬스체크 핑까지 보낸다. 관리자도 외부 감시도 장애를 감지하지 못한다. `failed` 플래그가 그 구분을 만든다.

### 구현체 3종 (전부 `collect` 패키지)

**1. `HackerNewsClient implements NewsSource`**

Algolia API를 쓴다. 인증 키가 필요 없다.

```
GET {baseUrl}/search_by_date
    ?tags=story
    &numericFilters=created_at_i>{sinceEpochSeconds},points>{minPoints}
    &query={keyword}
    &hitsPerPage={hitsPerPage}
```

- 기본 `baseUrl`: `https://hn.algolia.com/api/v1`
- 설정된 키워드 목록마다 한 번씩 호출하고 결과를 합친 뒤 `normalizedUrl` 기준으로 중복을 제거한다
- 응답 JSON의 `hits[]`에서 `title`, `url`, `points`, `created_at`을 읽는다
- **`url`이 null인 hit은 버린다.** 이유: Ask HN 같은 자체 글은 외부 원문이 없어 본문 크롤링(step 3)이 불가능하다
- `sourceName`은 `"Hacker News"`, `sourceDomain`은 원문 URL에서 뽑는다(`news.ycombinator.com`이 아니다)

**2. `RssFeedClient implements NewsSource`**

rome의 `SyndFeedInput`으로 RSS 2.0과 Atom을 모두 파싱한다. 설정된 피드 목록을 순회한다.

- 피드 하나가 실패해도 나머지는 계속 처리한다. 단 **`FetchResult.failed = true`로 표시한다** (부분 실패도 실패다)
- `publishedDate`가 없으면 `updatedDate`를 쓰고, 둘 다 없으면 그 항목을 버린다
- `since` 이전 항목은 제외한다
- `points`는 0
- `sourceName`은 피드 설정에 지정된 이름을 쓴다

**3. `ChangelogClient implements NewsSource`**

GitHub raw의 마크다운 CHANGELOG를 읽어 **가장 최신 버전 섹션 하나만** 후보로 만든다.

- 기본 URL: `https://raw.githubusercontent.com/anthropics/claude-code/main/CHANGELOG.md`
- `## <version>` 형태의 첫 번째 헤딩과 다음 헤딩 전까지의 본문을 취한다
- 제목은 `"Claude Code <version> 릴리즈"` 형태로 만든다
- **URL은 반드시 버전을 쿼리 파라미터로 포함시켜라**: `{changelogUrl}?v={version}`
  이유: `UrlNormalizer`가 fragment(`#2.1.222`)를 제거하므로 앵커를 쓰면 모든 버전이 같은
  URL로 정규화된다. 그러면 최초 1회 발송 후 영원히 중복으로 걸러진다. 쿼리 파라미터는
  추적 파라미터 목록에 없으므로 정규화 후에도 살아남는다
- 버전 문자열을 파싱할 수 없으면 `FetchResult.failure()`를 반환한다. 이유: CHANGELOG 형식이 바뀌었다는 뜻이고, 조용히 빈 결과로 넘어가면 이 소스가 죽은 채로 방치된다
- `publishedAt`은 파일에 날짜가 없으므로 `Instant.now()`를 쓴다. `since` 필터를 적용하지 않는다

### 설정

`src/main/resources/application.yml`에 다음을 추가하고 `@ConfigurationProperties`로 바인딩한다.

```yaml
ainewsdigest:
  collect:
    hacker-news:
      base-url: https://hn.algolia.com/api/v1
      min-points: 30
      hits-per-page: 40
      keywords:
        - claude code
        - codex
        - cursor
        - LLM
        - AI coding
    rss:
      feeds:
        - name: OpenAI
          url: https://openai.com/news/rss.xml
        - name: Simon Willison
          url: https://simonwillison.net/atom/everything/
    changelog:
      url: https://raw.githubusercontent.com/anthropics/claude-code/main/CHANGELOG.md

spring:
  http:
    client:
      connect-timeout: 5s
      read-timeout: 10s
```

프로퍼티 클래스에는 `@ConfigurationProperties`만 붙인다. `@Component`나 `@EnableConfigurationProperties`를 쓰지 마라 — `AinewsdigestApplication`에 `@ConfigurationPropertiesScan`이 이미 걸려 있다. 등록 방식이 도메인마다 갈리면 어디를 봐야 바인딩되는지 알 수 없게 된다.

### HTTP 클라이언트 구성 — 반드시 지킬 것

`RestClient`는 `build.gradle.kts`의 **`spring-boot-starter-restclient`**가 제공한다. Boot 4는 스타터가 잘게 쪼개져서 `spring-boot-starter-webmvc`에는 RestClient가 들어 있지 않다 (ADR-015). 이 의존성을 지우지 마라.

위 `spring.http.client.*`는 **기본값일 뿐이다.** 어댑터는 주입받은 빌더에서 자기 타임아웃을 지정해 `RestClient`를 만든다:

```java
// RestClient.Builder, ClientHttpRequestFactoryBuilder<?>, HttpClientSettings 전부 자동 구성 빈이다
public HackerNewsClient(RestClient.Builder builder,
                        ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                        HttpClientSettings defaults,
                        HackerNewsProperties props) {
    this.restClient = builder
            .baseUrl(props.baseUrl())
            .requestFactory(factoryBuilder.build(defaults.withReadTimeout(Duration.ofSeconds(10))))
            .build();
}
```

Boot 4의 타입 이름은 `org.springframework.boot.http.client.HttpClientSettings`다. Boot 3.4~3.5의 `ClientHttpRequestFactorySettings`를 쓰지 마라 — 그 클래스는 없다.

전역 `ClientHttpRequestFactory` 설정 클래스는 만들지 마라. step 4(OpenAI 60s)와 step 7(롱폴링 40s)이 서로 다른 타임아웃을 요구하므로 공용 설정을 두면 어차피 어댑터마다 덮어쓰게 된다.

## 테스트

`src/test/java/com/example/ainewsdigest/collect/` 하위. **WireMock으로 HTTP를 스텁하고 실제 외부 서버를 호출하지 않는다.**

응답 픽스처는 `src/test/resources/fixtures/` 에 파일로 둔다:
`hn-search.json`, `openai-rss.xml`, `simonwillison-atom.xml`, `claude-code-changelog.md`

검증 항목:
1. `HackerNewsClient`가 hits를 `CandidateArticle`로 변환하고 `normalizedUrl`·`sourceDomain`을 채운다
2. `HackerNewsClient`가 `url`이 null인 hit을 버린다
3. `HackerNewsClient`가 키워드 2개면 2회 호출하고, 같은 기사가 양쪽에 나오면 1건으로 합친다
4. `RssFeedClient`가 RSS 2.0(OpenAI)과 Atom(Simon Willison)을 모두 파싱한다
5. `RssFeedClient`가 `since` 이전 항목을 제외한다
6. 피드 하나가 500을 반환해도 다른 피드 결과는 반환된다
7. `ChangelogClient`가 최신 버전 섹션을 뽑고 URL에 `?v=<version>`이 붙는다
8. **버전이 다르면 `normalizedUrl`도 달라진다** (중복 제거에 걸리지 않아야 한다)
9. 어떤 소스든 HTTP 500·타임아웃 시 예외를 던지지 않고 `FetchResult.failed == true`를 반환한다
10. **정상 응답이지만 결과가 0건이면 `failed == false`다** (성공한 빈 결과와 실패를 구분한다)
11. `RssFeedClient`에서 피드 2개 중 1개만 500이면 다른 피드 결과가 담기고 `failed == true`다
12. **어댑터 3종 모두 `normalizedUrl`·`sourceDomain`이 빈 문자열이 아니다** (`CandidateArticle.of` 사용 여부 검증)

## Acceptance Criteria

```bash
./gradlew build   # Git Bash 기준. PowerShell/cmd에서는 gradlew.bat build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다. (Docker Desktop이 실행 중이어야 한다)
2. 아키텍처 체크리스트를 확인한다:
   - ARCHITECTURE.md 디렉토리 구조를 따르는가?
   - ADR 기술 스택을 벗어나지 않았는가?
   - CLAUDE.md CRITICAL 규칙을 위반하지 않았는가? (특히 인터페이스 뒤에 두기)
3. 결과에 따라 `phases/0-mvp/index.json`의 step 2를 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 테스트에서 실제 외부 API(hn.algolia.com, openai.com 등)를 호출하지 마라. 이유: CI가 네트워크와 외부 서비스 상태에 종속되어 빌드가 무작위로 실패한다. 반드시 WireMock을 쓴다
- 기사 본문을 크롤링하지 마라. 이유: step 3의 범위다. 이 step은 제목·URL·메타데이터만 수집한다
- LLM을 호출하지 마라. 이유: step 4의 범위다
- 수집 결과를 DB에 저장하지 마라. 이유: 후보 기사는 저장하지 않기로 했다(PRD MVP 제외 사항). 선별된 것만 step 6에서 저장한다
- 소스별 URL을 코드에 하드코딩하지 마라. 이유: 설정으로 빼야 소스 추가·교체가 재빌드 없이 가능하다
- 실패를 빈 리스트로 표현하지 마라. 이유: ADR-016. 수집 전면 장애가 "뉴스 없는 날"로 위장되어 헬스체크까지 초록불이 된다
- `CandidateArticle`을 생성자로 직접 만들지 마라. 이유: 정규화 필드를 빠뜨리면 중복 제거가 조용히 무너진다. `CandidateArticle.of(...)`만 쓴다
- `spring-boot-starter-restclient` 의존성을 제거하지 마라. 이유: Boot 4에서 `RestClient.Builder`를 제공하는 유일한 스타터다. 빼면 세 어댑터가 전부 기동 실패한다
- 기존 테스트를 깨뜨리지 마라
