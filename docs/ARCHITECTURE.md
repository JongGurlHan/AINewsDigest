# 아키텍처

## 디렉토리 구조
```
src/
├── main/
│   ├── java/com/example/ainewsdigest/
│   │   ├── subscription/    # 구독자, /start /stop 처리
│   │   ├── digest/          # 다이제스트 엔티티·아카이브 화면·생성 오케스트레이션
│   │   ├── collect/         # 외부 소스 수집(HN/RSS/CHANGELOG) + 본문 크롤링
│   │   ├── curation/        # OpenAI 선별·요약
│   │   ├── delivery/        # 텔레그램 발송·롱폴링·발송 이력
│   │   ├── global/          # 공통 설정, 예외 처리, 관리자 알림, 헬스핑
│   │   └── AinewsdigestApplication.java
│   └── resources/
│       ├── templates/       # Thymeleaf 템플릿 (fragments/ 하위에 공통 조각)
│       ├── static/          # CSS, JS, 이미지
│       ├── db/migration/    # Flyway 마이그레이션
│       └── application.yml  # 운영 설정 (PostgreSQL)
└── test/
    ├── java/com/example/ainewsdigest/
    └── resources/
        ├── application.yml  # 테스트 설정 (Testcontainers PostgreSQL, Flyway 활성)
        └── fixtures/        # HN·RSS·기사 HTML 응답 픽스처
```

각 도메인 패키지 안에 controller / service / repository / entity / dto를 둔다(도메인별 계층형).

## 패턴
- 계층형 아키텍처 + 도메인별 패키지 구성
- **아웃바운드 5개만 인터페이스로 추상화한다**: `NewsSource`(HN·RSS·CHANGELOG), `ArticleContentExtractor`(jsoup), `ArticleSelector`·`ArticleSummarizer`(OpenAI), `Messenger`(Telegram). 서비스 계층은 인터페이스에만 의존하고, 테스트는 인메모리 페이크로 대체한다
- 인바운드(Controller, `@Scheduled`, 텔레그램 폴러)는 추상화하지 않는다. 과설계를 피한다
- `@Transactional`은 Service 계층에만 부착한다

### 아웃바운드 포트의 오류 표현 (ADR-016)

포트마다 실패를 다르게 다룬다. 기준은 **"그 실패로 오늘의 다이제스트가 성립하는가"** 하나다.

| 포트 | 실패 표현 | 이유 |
|---|---|---|
| `NewsSource.fetch` | `FetchResult(articles, failed)` | 소스 하나가 죽어도 나머지로 다이제스트가 성립한다 |
| `ArticleContentExtractor.extract` | `Optional.empty()` | 페이월·봇차단·SSRF 차단은 정상 흐름. 해당 기사만 탈락 |
| `Messenger.send` | `SendResult` (sealed) | 구독자 한 명의 실패가 전체 순회를 멈추면 안 된다 |
| `UpdateSource.getUpdates` | `PollResult` (sealed) | 폴러가 실패와 "업데이트 없음"을 구분해야 백오프를 건다 |
| `ArticleSelector` · `ArticleSummarizer` | **예외 전파** | 선별·요약이 실패하면 그날 다이제스트 자체가 성립하지 않는다 |

**빈 컬렉션으로 실패를 표현하지 마라.** "결과가 없음"과 "실패해서 없음"이 같은 값이 되면 호출자가 둘을 구분할 수 없고, 장애가 정상 동작으로 위장된다. `NewsSource`와 `UpdateSource`가 정확히 이 문제 때문에 결과 타입을 쓴다.

### 도메인 간 접근

- 다른 도메인의 리포지토리를 **읽는 것은 허용한다.** ADR-012의 계층형 선택에 따른 것이며, 테이블 4개짜리 MVP에 위임 서비스 계층을 한 겹 더 두지 않는다
- 다만 **재사용되는 조회는 소유 도메인의 Service에만 둔다.** 같은 쿼리를 두 도메인이 각자 구현하지 않는다
  - 예: "가장 최근에 발송된 내용 있는 다이제스트"는 `digest.DigestQueryService`에만 둔다. 봇의 `/start` 응답(step 8)과 웹 랜딩(step 10)이 같은 메서드를 쓴다. 규칙이 한쪽에서만 바뀌면 봇과 웹이 서로 다른 것을 보여주게 된다
- 다른 도메인 엔티티의 **상태 변경은 그 엔티티의 도메인 메서드로만** 한다 (`subscriber.unsubscribe()` 등). 공개 setter를 만들지 않은 이유가 이것이다

## 데이터 흐름

### 웹 조회
```
HTTP 요청 → Controller → Service(@Transactional(readOnly=true)) → Repository
          → DTO 변환 → Thymeleaf 렌더링
```

### 다이제스트 생성 (매일 07:00 KST)
```
Scheduler → DigestGenerationService
  1. 오늘자 Digest가 이미 있으면 중단 (멱등성: digest.digest_date UNIQUE)
  2. NewsSource 구현체들에서 지난 24시간 후보 수집 (실패한 소스 수를 센다)
  3. UrlNormalizer로 정규화 → 최근 7일 digest_item.normalized_url과 대조해 중복 제거
  4. ArticleSelector: 후보 제목·출처·points만 LLM에 전달 → 1~5점 채점 → 상위 8건
  5. ArticleContentExtractor: 8건 본문 크롤링 (타임아웃 5초, 실패 시 해당 건 탈락)
     크롤링 대상 URL은 HN에 아무나 올린 것이다. SafeUrlPolicy로 스킴·목적지 IP를
     검사하고 리다이렉트를 직접 추적한다 (ADR-017)
  6. 3점 이상 & 본문 확보된 것 중 상위 3~5건 선정
  7. 0건이면 Digest(status=EMPTY)로 저장하고 종료 (EMPTY도 발송 대상이다 — 아래 "다이제스트 상태 규칙")
  8. ArticleSummarizer: 한글 제목 + 요약(건당 600자 이내) 생성
  9. DigestMessageBuilder: HTML 조립 → 4,000자 초과 시 하위 순위부터 제거
 10. Digest(status=PENDING) + DigestItem 저장
```

생성 단계에서는 헬스체크 핑을 보내지 않는다. 핑은 발송 성공 후에만 보낸다(ADR-010). 생성 시점에 같은 핑을 보내면 07:30 발송이 통째로 실패해도 외부에서는 정상으로 관측된다.

**수집 전면 실패는 "뉴스 없음"과 반드시 구분한다.** 소스 3개가 전부 죽어도 후보는 0건이고, 그대로 두면 EMPTY 다이제스트가 정상 발송되고 헬스체크 핑까지 나간다 — 관리자도 외부 감시도 아무 이상을 못 느낀다. ADR-010이 막으려던 사각지대가 정확히 여기다. 그래서 `GenerationResult`가 `candidateCount`와 `failedSourceCount`를 같이 돌려주고, 스케줄러가 아래 규칙으로 판정한다:

| 조건 | 해석 | 조치 |
|---|---|---|
| `candidateCount == 0` && `failedSourceCount > 0` | 수집 전면 실패 의심 | 관리자 failure 알림 |
| `candidateCount == 0` && `failedSourceCount == 0` | 진짜 조용한 날 | 정상. EMPTY 발송 |
| `failedSourceCount > 0` (후보는 있음) | 일부 소스 장애 | 관리자 warning 알림 |

### 다이제스트 발송 (매일 07:30 KST)
```
Scheduler → DigestSendService
  1. 오늘자 미발송 Digest(sent_at IS NULL) 조회. PENDING·EMPTY 모두 발송 대상이다
       없으면 "다이제스트 없음"으로 보고하고 종료 (관리자 알림은 스케줄러가 한다)
       이미 sent_at이 채워져 있으면 아무것도 하지 않는다 (멱등성)
  2. status=ACTIVE 구독자 조회
  3. 이 Digest에 대해 이미 SUCCESS로 기록된 구독자를 순회 대상에서 제외한다 (재개)
  4. 구독자별 Messenger.send() 호출
       403 Forbidden → 해당 구독자 UNSUBSCRIBED 전환
       429 Too Many Requests → retry_after(최대 60초)만큼 대기 후 재시도
       5xx → 지수 백오프 최대 3회
       DeliveryLog 기록
  5. 한 건이라도 성공했으면 sent_at 기록 (조건은 아래 "다이제스트 상태 규칙")
       전원 실패면 sent_at을 비워 둔다 — 재실행 여지를 남긴다 (ADR-018)
  6. 헬스체크 핑 전송. 실패율이 높으면 관리자에게 알림
```

**전원이 실패한 발송을 "발송됨"으로 기록하지 마라.** 순회를 끝냈다는 것과 발송됐다는 것은 다르다. 텔레그램이 죽은 날 `sent_at`을 채우면 절차 1의 멱등성 체크가 재실행을 영구히 막고, 아무도 받지 못한 다이제스트가 아카이브에 발송 완료로 남는다. 관리자가 수동 재실행해도 아무 일이 일어나지 않는다 (ADR-018).

### 구독 (상시)
```
TelegramUpdatePoller (백그라운드 롱폴링, getUpdates)
  /start [payload] → 구독자 생성 또는 재활성화 → 환영 메시지 + 최근 다이제스트 발송
  /stop            → status=UNSUBSCRIBED
  /help            → 안내 메시지
```

## 다이제스트 상태 규칙

**발송 여부는 `status`가 아니라 `sent_at`으로 판정한다.** `status`는 콘텐츠의 성격(항목이 있는가 / 뉴스가 없었는가)을 나타내고 `sent_at`은 발송 여부를 나타낸다. 두 축이 섞이면 EMPTY를 SENT로 덮어쓰는 순간 "그날 뉴스가 없었다"는 정보가 사라진다.

```
markSent(now):  PENDING -> SENT,  sentAt = now
                EMPTY   -> EMPTY, sentAt = now   (status 보존)

markSent 호출 조건 : succeeded > 0 || skipped > 0 || 시도 대상 0명   (ADR-018)
                     전원 실패면 호출하지 않는다 — sentAt은 null로 남는다

발송 대상     : sentAt == null    (PENDING·EMPTY 모두)
아카이브 노출 : sentAt != null
"뉴스 없음"   : status == EMPTY
```

- `sent_at` 컬럼은 `V1__init.sql`에 이미 있다. 이 규칙에 스키마 변경은 필요 없다
- EMPTY로 나간 날이 DB에 그대로 남으므로 ADR-013의 점수 임계값을 튜닝할 때 발송 빈도 지표로 쓸 수 있다
- `FAILED`는 스키마의 예약값이며 MVP 흐름에서는 저장되지 않는다. 생성이 실패하면 Digest 행 자체를 만들지 않는다. FAILED로 저장하면 `digest_date` UNIQUE와 생성 멱등성 체크에 걸려 그날의 수동 재실행이 영구히 막힌다

### 발송 보장 수준 — at-least-once

텔레그램 API 호출과 `DeliveryLog` 커밋 사이에 프로세스가 죽으면 실제 발송 여부를 알 수 없으므로 exactly-once는 불가능하다. 대신 **이미 SUCCESS 로그가 있는 구독자를 건너뛰는 재개 규칙**으로 중복을 억제한다. 커밋 직전에 죽은 1건은 재실행 시 중복 발송될 수 있으며, 이는 알려진 한계로 감수한다.

## 트랜잭션 경계
- Service 메서드 단위로 열고 닫는다. 조회 전용 메서드는 `readOnly = true`
- Controller와 Repository에는 `@Transactional`을 붙이지 않는다
- **외부 API 호출(OpenAI, Telegram, HN, RSS, 기사 크롤링)은 트랜잭션 밖에서 수행한다.** 응답을 다 받은 뒤에 트랜잭션을 열어 저장한다. 네트워크 대기 중 DB 커넥션을 점유하면 커넥션 풀이 마른다
- 발송은 구독자 단위로 트랜잭션을 분리한다. 한 명의 발송 실패가 다른 구독자의 `DeliveryLog` 기록을 롤백시키면 안 된다

## 외부 HTTP 클라이언트

`RestClient`를 쓴다. **Boot 4는 모듈이 쪼개져서 `spring-boot-starter-webmvc`가 RestClient를 가져오지 않는다.** `RestClient.Builder` 빈과 `spring.http.client.*` 프로퍼티는 `spring-boot-starter-restclient`에만 들어 있다 (ADR-015). 이 의존성을 제거하지 말 것.

### 타임아웃은 어댑터마다 다르다

전역값 하나로 맞출 수 없다. 롱폴링은 30초 넘게 기다려야 정상이고, LLM 요약은 60초가 필요하며, 수집은 10초 안에 포기해야 한다.

| 어댑터 | read timeout | 근거 |
|---|---|---|
| `HackerNewsClient` · `RssFeedClient` · `ChangelogClient` | 10s | 느린 소스 하나가 배치를 잡아두면 안 된다. 실패하면 그 소스만 버린다 |
| `OpenAiClient` | 60s | 요약 호출은 수십 초가 걸린다. 짧으면 매일 아침 타임아웃이다 |
| `TelegramClient.sendMessage` | 10s | |
| `TelegramClient.getUpdates` | `poll-timeout` + 10s | **읽기 타임아웃이 폴링 타임아웃보다 짧으면 매 사이클 예외가 나고 구독 기능이 통째로 죽는다** |

`spring.http.client.*`는 기본값으로만 두고, 각 어댑터가 생성자에서 자기 타임아웃을 지정한다:

```java
// 두 빈 모두 Boot가 자동 구성한다
public OpenAiClient(RestClient.Builder builder,
                    ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                    HttpClientSettings defaults,
                    OpenAiProperties props) {
    this.restClient = builder
            .baseUrl(props.baseUrl())
            .requestFactory(factoryBuilder.build(defaults.withReadTimeout(props.timeout())))
            .build();
}
```

Boot 4의 타입은 `HttpClientSettings`다. Boot 3.4~3.5의 `ClientHttpRequestFactorySettings`가 아니다.

전역 `ClientHttpRequestFactory` 설정 클래스를 따로 만들지 마라. 어댑터가 세 종류뿐이고 전부 타임아웃이 달라서, 공용 설정 클래스를 두면 결국 어댑터마다 덮어쓰게 된다.

## 스케줄링
- 모든 `@Scheduled`에 `zone = "Asia/Seoul"`을 명시한다. 서버(Oracle VM)의 기본 타임존은 UTC이므로 존을 지정하지 않으면 9시간 어긋난다
- 생성(07:00)과 발송(07:30)을 분리한다. 발송 시각이 외부 API 응답 속도에 영향받지 않게 하고, 생성 실패 시 30분의 복구 여유를 남긴다

### 세 시각의 역할

| 시각 | 작업 | 비고 |
|---|---|---|
| 07:00 | 생성 | 실패하면 관리자에게 failure 알림 |
| 07:15 | **생성 재시도** | 오늘자 다이제스트가 이미 있으면 즉시 반환(무해). 복구되면 warning으로 알린다 |
| 07:30 | 발송 | PENDING·EMPTY 모두 대상 |

07:15가 "30분의 복구 여유"를 실제로 쓰는 유일한 장치다. 이것이 없으면 07:00 생성 실패는 곧 그날 발송 없음이고, 복구 수단이 SSH 수동 실행뿐이다. 이 프로젝트의 철학("사람이 개입해야만 복구되는 구조는 만들지 않는다")과 정면으로 어긋난다.

### 스케줄러 스레드 풀은 1로 둔다

`spring.task.scheduling.pool.size: 1`. Boot 기본값이며 **의도적으로 유지한다.**

생성이 길어져 07:30을 넘겨도 발송은 동시 실행되지 않고 생성이 끝난 뒤 지연 실행된다. 이게 옳은 동작이다. 풀을 늘리면 생성이 진행 중인 상태에서 발송이 시작돼 다이제스트를 찾지 못하고, 관리자에게 "다이제스트 없음" 오탐 알림이 간다. 작업이 세 개(07:00 / 07:15 / 07:30)로 늘어난 뒤로 이 순차성이 더 중요해졌다.
