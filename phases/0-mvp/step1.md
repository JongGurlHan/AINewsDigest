# Step 1: url-normalizer

## 읽어야 할 파일

- `/docs/ARCHITECTURE.md` — `collect` 패키지의 역할
- `/docs/ADR.md` — ADR-005(수집 소스), ADR-013(선별 기준)
- `/src/main/java/com/example/ainewsdigest/digest/` — step 0에서 만든 `DigestRepository`의 `findNormalizedUrlsSince`
- `/src/main/resources/db/migration/V1__init.sql` — `digest_item.normalized_url` 컬럼과 인덱스

## 작업

같은 기사가 여러 소스에서 서로 다른 URL로 들어오거나 며칠에 걸쳐 반복 등장하는 것을 막는 URL 정규화기를 만든다. 이것이 중복 발송 방지의 1차 방어선이다.

### 파일

`com.example.ainewsdigest.collect.UrlNormalizer` — 스프링 빈(`@Component`), 상태 없음.

```java
public class UrlNormalizer {
    /**
     * 중복 판정에 쓸 정규화 형태를 만든다.
     * 파싱 불가능한 입력이면 원본을 트림해서 그대로 돌려준다 (예외를 던지지 않는다).
     */
    public String normalize(String rawUrl);
}
```

### 정규화 규칙

| 규칙 | 예시 |
|---|---|
| scheme·host 소문자화 | `HTTPS://Example.COM/A` → `https://example.com/A` |
| `http` → `https`로 통일 | `http://a.com/x` → `https://a.com/x` |
| 선행 `www.` 제거 | `https://www.a.com/x` → `https://a.com/x` |
| 기본 포트 제거 | `https://a.com:443/x` → `https://a.com/x` |
| fragment 제거 | `https://a.com/x#intro` → `https://a.com/x` |
| 추적 파라미터 제거 | `utm_*`, `fbclid`, `gclid`, `ref`, `ref_src`, `source`, `mc_cid`, `mc_eid` |
| 남은 쿼리 파라미터는 이름 오름차순 정렬 | `?b=2&a=1` → `?a=1&b=2` |
| 쿼리가 비면 `?` 자체를 제거 | `https://a.com/x?utm_source=hn` → `https://a.com/x` |
| 후행 슬래시 제거 (경로가 `/`뿐이면 유지) | `https://a.com/x/` → `https://a.com/x` |

**경로(path)의 대소문자는 보존한다.** 이유: 상당수 사이트에서 경로는 대소문자를 구분하며, 소문자화하면 서로 다른 문서를 같은 것으로 판정한다.

### 도메인 추출

같은 클래스에 출처 표기용 도메인 추출도 넣는다. 메시지에 `cursor.com` 형태로 노출된다(ADR-009).

```java
/** 표시용 도메인. www. 를 떼고 소문자로 반환한다. 파싱 실패 시 빈 문자열. */
public String extractDomain(String rawUrl);
```

## 테스트

`src/test/java/com/example/ainewsdigest/collect/UrlNormalizerTest.java` — **네트워크를 쓰지 않는 순수 단위 테스트**.

JUnit 5 `@ParameterizedTest` + `@CsvSource`로 위 표의 규칙을 각각 검증하고, 추가로:

1. 추적 파라미터가 섞인 두 URL이 같은 값으로 정규화된다
   (`https://a.com/x?utm_source=hn&id=5` 와 `https://www.a.com/x/?id=5#top` → 동일)
2. 경로 대소문자가 다르면 **다른** 값으로 정규화된다 (`/Post` ≠ `/post`)
3. `null`, 빈 문자열, `"not a url"` 입력에 예외를 던지지 않는다
4. `extractDomain`이 `https://www.techcrunch.com/2026/x` → `techcrunch.com`

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
3. 결과에 따라 `phases/0-mvp/index.json`의 step 1을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 외부 라이브러리를 추가하지 마라. 이유: `java.net.URI`로 충분하다
- 네트워크 요청을 하지 마라 (리다이렉트 추적, canonical URL 조회 등). 이유: 정규화는 순수 함수여야 한다. 배치에서 수십 번 호출되며 네트워크가 끼면 느려지고 테스트가 불안정해진다
- 기사 수집 클라이언트나 크롤러를 만들지 마라. 이유: step 2, 3의 범위다
- 기존 테스트를 깨뜨리지 마라
