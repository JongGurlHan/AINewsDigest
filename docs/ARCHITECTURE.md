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
  7. 0건이면 Digest(status=EMPTY)로 저장하고 종료
  8. ArticleSummarizer: 한글 제목 + 요약(건당 600자 이내) 생성
  9. DigestMessageBuilder: HTML 조립 → 4,000자 초과 시 하위 순위부터 제거
 10. Digest(status=PENDING) + DigestItem 저장
 11. 헬스체크 핑 전송
```

### 다이제스트 발송 (매일 07:30 KST)
```
Scheduler → DigestSendService
  1. 오늘자 PENDING Digest 조회. 없으면 관리자에게 알림 후 종료
  2. status=ACTIVE 구독자 조회
  3. 구독자별 Messenger.send() 호출
       403 Forbidden → 해당 구독자 UNSUBSCRIBED 전환
       429 Too Many Requests → 응답의 retry_after만큼 대기 후 재시도
       5xx → 지수 백오프 최대 3회
       DeliveryLog 기록
  4. Digest(status=SENT, sent_at) 갱신
  5. 헬스체크 핑 전송. 실패율이 높으면 관리자에게 알림
```

### 구독 (상시)
```
TelegramUpdatePoller (백그라운드 롱폴링, getUpdates)
  /start [payload] → 구독자 생성 또는 재활성화 → 환영 메시지 + 최근 다이제스트 발송
  /stop            → status=UNSUBSCRIBED
  /help            → 안내 메시지
```

## 트랜잭션 경계
- Service 메서드 단위로 열고 닫는다. 조회 전용 메서드는 `readOnly = true`
- Controller와 Repository에는 `@Transactional`을 붙이지 않는다
- **외부 API 호출(OpenAI, Telegram, HN, RSS, 기사 크롤링)은 트랜잭션 밖에서 수행한다.** 응답을 다 받은 뒤에 트랜잭션을 열어 저장한다. 네트워크 대기 중 DB 커넥션을 점유하면 커넥션 풀이 마른다
- 발송은 구독자 단위로 트랜잭션을 분리한다. 한 명의 발송 실패가 다른 구독자의 `DeliveryLog` 기록을 롤백시키면 안 된다

## 스케줄링
- 모든 `@Scheduled`에 `zone = "Asia/Seoul"`을 명시한다. 서버(Oracle VM)의 기본 타임존은 UTC이므로 존을 지정하지 않으면 9시간 어긋난다
- 생성(07:00)과 발송(07:30)을 분리한다. 발송 시각이 외부 API 응답 속도에 영향받지 않게 하고, 생성 실패 시 30분의 복구 여유를 남긴다
