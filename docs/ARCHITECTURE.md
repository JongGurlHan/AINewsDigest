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
  2. NewsSource 구현체들에서 지난 24시간 후보 수집
  3. UrlNormalizer로 정규화 → 최근 7일 digest_item.normalized_url과 대조해 중복 제거
  4. ArticleSelector: 후보 제목·출처·points만 LLM에 전달 → 1~5점 채점 → 상위 8건
  5. ArticleContentExtractor: 8건 본문 크롤링 (타임아웃 5초, 실패 시 해당 건 탈락)
  6. 3점 이상 & 본문 확보된 것 중 상위 3~5건 선정
  7. 0건이면 Digest(status=EMPTY)로 저장하고 종료 (EMPTY도 발송 대상이다 — 아래 "다이제스트 상태 규칙")
  8. ArticleSummarizer: 한글 제목 + 요약(건당 600자 이내) 생성
  9. DigestMessageBuilder: HTML 조립 → 4,000자 초과 시 하위 순위부터 제거
 10. Digest(status=PENDING) + DigestItem 저장
```

생성 단계에서는 헬스체크 핑을 보내지 않는다. 핑은 발송 성공 후에만 보낸다(ADR-010). 생성 시점에 같은 핑을 보내면 07:30 발송이 통째로 실패해도 외부에서는 정상으로 관측된다.

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
       429 Too Many Requests → 응답의 retry_after만큼 대기 후 재시도
       5xx → 지수 백오프 최대 3회
       DeliveryLog 기록
  5. sent_at 기록 (상태 전이는 아래 "다이제스트 상태 규칙")
  6. 헬스체크 핑 전송. 실패율이 높으면 관리자에게 알림
```

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

## 스케줄링
- 모든 `@Scheduled`에 `zone = "Asia/Seoul"`을 명시한다. 서버(Oracle VM)의 기본 타임존은 UTC이므로 존을 지정하지 않으면 9시간 어긋난다
- 생성(07:00)과 발송(07:30)을 분리한다. 발송 시각이 외부 API 응답 속도에 영향받지 않게 하고, 생성 실패 시 30분의 복구 여유를 남긴다
