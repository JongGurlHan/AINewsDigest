# Step 6: digest-generation

## 읽어야 할 파일

- `/docs/ARCHITECTURE.md` — **"다이제스트 생성" 데이터 흐름 절차 11단계. 이 step이 그것이다**
- `/docs/ADR.md` — ADR-007(2단계 LLM), ADR-013(점수 임계값)
- `/docs/PRD.md` — 발송 규칙(3~5건, 0건 처리)
- step 0~5의 모든 산출물:
  - `/src/main/java/com/example/ainewsdigest/digest/` — `Digest`, `DigestItem`, `DigestRepository`, `DigestMessageBuilder`
  - `/src/main/java/com/example/ainewsdigest/collect/` — `NewsSource`, `CandidateArticle`, `UrlNormalizer`, `ArticleContentExtractor`
  - `/src/main/java/com/example/ainewsdigest/curation/` — `ArticleSelector`, `ArticleSummarizer`, 값 객체들

## 작업

수집부터 저장까지를 잇는 오케스트레이션 서비스를 만든다. 새 외부 어댑터를 만들지 않고 기존 포트들을 조립한다.

### 파일

`com.example.ainewsdigest.digest.DigestGenerationService`

```java
/** 해당 날짜의 다이제스트를 생성해 PENDING(또는 EMPTY) 상태로 저장한다. */
public GenerationResult generate(LocalDate date);

public record GenerationResult(DigestStatus status, int itemCount, int candidateCount) {}
```

### 절차 (ARCHITECTURE.md의 흐름을 그대로 구현)

1. `digestRepository.existsByDigestDate(date)` 가 true면 **아무것도 하지 않고 즉시 반환한다** (멱등성)
2. `since = now - 24시간`. 모든 `NewsSource`를 순회해 후보를 모은다 (`List<NewsSource>`를 주입받는다)
3. `normalizedUrl` 기준으로 후보 내 중복을 제거한다
4. `digestRepository.findNormalizedUrlsSince(date.minusDays(7))` 결과에 포함된 후보를 제외한다
5. 후보가 0건이면 EMPTY로 저장하고 종료
6. `articleSelector.scoreAndRank(candidates, recentTitles)` 호출.
   `recentTitles`는 `findTitlesSince(date.minusDays(7))`
7. **`score >= minScore(3)` 인 것만 남기고**, 상위 `selectCount(8)`건을 취한다
8. 그 8건에 대해서만 `articleContentExtractor.extract(url)`를 호출한다. 빈 값이면 탈락
9. 남은 것 중 상위 `maxItems(5)`건을 선정한다
10. 0건이면 `Digest.empty(date, 뉴스없음메시지)`를 저장하고 종료 (status = EMPTY).
    **EMPTY는 "끝난 것"이 아니라 step 9의 발송 대상이다.** `sentAt`이 비어 있으므로 07:30에 그대로 발송된다 (PRD: 침묵하지 않는다)
11. `articleSummarizer.summarize(...)` 로 한글 제목·요약 생성
12. `digestMessageBuilder.build(date, summarized)` 로 메시지 조립.
    **빌더가 길이 때문에 항목을 줄였다면(`includedCount`), 저장하는 `DigestItem`도 그 건수에 맞춘다.**
    이유: 아카이브 웹페이지가 실제 발송 내용과 달라지면 안 된다
13. `Digest.pending(...)` + `DigestItem`들을 저장한다

### 트랜잭션 경계 — 반드시 지킬 것

**`generate()` 메서드 전체에 `@Transactional`을 붙이지 마라.** (CLAUDE.md CRITICAL)

이 메서드는 HTTP 호출을 수십 번 한다(수집 + 크롤링 8회 + LLM 2회). 전체를 트랜잭션으로 감싸면 DB 커넥션을 수 분간 점유해 커넥션 풀이 마른다.

구조는 이렇게 한다:
- 조회(1, 4, 6단계의 `recentUrls`/`recentTitles`)는 각각 짧은 `readOnly` 트랜잭션으로 먼저 끝낸다
- 외부 호출은 트랜잭션 **밖**에서 한다
- 마지막 저장(13단계)만 별도의 `@Transactional` 메서드로 분리한다

### 실패 처리

- `ArticleSelector`/`ArticleSummarizer`가 예외를 던지면 그대로 밖으로 전파한다. 스케줄러(step 11)가 재시도와 알림을 결정한다
- 개별 `NewsSource.fetch`는 이미 빈 리스트를 반환하도록 되어 있으므로 여기서 예외 처리하지 않는다
- 크롤링 실패는 정상 흐름이다. 해당 기사만 탈락시키고 계속 진행한다

## 테스트

`src/test/java/com/example/ainewsdigest/digest/DigestGenerationServiceTest.java`

**모든 아웃바운드 포트를 인메모리 페이크로 대체한다.** 페이크는 `src/test/java/.../support/` 에 두고 재사용 가능하게 만든다. (Mockito를 써도 되지만, 시나리오가 상태 기반이라 페이크가 읽기 쉽다.)

DB는 Testcontainers를 쓴다 — `@SpringBootTest` + `@Import(TestcontainersConfig.class)`, 포트만 페이크로 교체(`@TestConfiguration` 또는 `@MockitoBean`).

시나리오 4종:

1. **정상** — 후보 20건 → 선별 8건 → 크롤링 전부 성공 → 5건 저장, status=PENDING, `DigestItem` 5개
2. **크롤링 부분 실패** — 선별 8건 중 5건 크롤링 실패 → 남은 3건으로 다이제스트 생성 (3건은 유효한 결과다)
3. **EMPTY** — 모든 후보의 점수가 2점 이하 → status=EMPTY, `messageText`가 `오늘의 AI 뉴스는 없습니다.`, `DigestItem` 0개, **요약기가 호출되지 않는다**
4. **멱등** — 같은 날짜로 `generate()`를 두 번 호출하면 두 번째는 아무 일도 하지 않고 `DigestItem` 개수가 늘지 않으며 **외부 포트가 한 번도 호출되지 않는다**

추가 검증:
5. 최근 7일 내 발송된 `normalizedUrl`을 가진 후보는 선별기에 전달되지 않는다
6. 점수 3점 미만인 후보는 **크롤링되지 않는다** (크롤러 호출 횟수로 검증 — 비용·시간 절감이 ADR-007의 요점이다)

## Acceptance Criteria

```bash
./gradlew build   # Git Bash 기준. PowerShell/cmd에서는 gradlew.bat build
```

## 검증 절차

1. 위 AC 커맨드를 실행한다. (Docker Desktop이 실행 중이어야 한다)
2. 아키텍처 체크리스트를 확인한다:
   - ARCHITECTURE.md 디렉토리 구조와 데이터 흐름을 따르는가?
   - ADR 기술 스택을 벗어나지 않았는가?
   - CLAUDE.md CRITICAL 규칙을 위반하지 않았는가? (특히 외부 호출과 트랜잭션 분리)
3. 결과에 따라 `phases/0-mvp/index.json`의 step 6을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `generate()` 전체에 `@Transactional`을 붙이지 마라. 이유: 위에 설명했다. 외부 HTTP 호출이 트랜잭션 안에 들어가면 커넥션 풀이 고갈된다
- 크롤링을 선별 **전에** 하지 마라. 이유: ADR-007의 핵심이 크롤링 횟수를 줄이는 것이다. 후보 전체를 크롤링하면 이 설계의 이점이 사라진다
- 새 외부 어댑터나 HTTP 클라이언트를 만들지 마라. 이유: 기존 포트를 조립하는 것이 이 step의 범위다
- 텔레그램으로 발송하지 마라. 이유: step 9의 범위다. 이 step은 PENDING 상태로 저장만 한다
- `@Scheduled`를 붙이지 마라. 이유: step 11의 범위다
- 후보 기사 전체를 DB에 저장하지 마라. 이유: PRD MVP 제외 사항이다
- 기존 테스트를 깨뜨리지 마라
