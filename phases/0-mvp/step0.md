# Step 0: domain-entities

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/ARCHITECTURE.md` — 디렉토리 구조, 트랜잭션 경계
- `/docs/ADR.md` — ADR-011(Testcontainers), ADR-012(5도메인 계층형)
- `/src/main/resources/db/migration/V1__init.sql` — **이 스키마가 정답이다. 엔티티를 여기에 맞춘다**
- `/src/test/java/com/example/ainewsdigest/support/TestcontainersConfig.java` — 테스트에서 DB를 붙이는 방법
- `/src/test/java/com/example/ainewsdigest/FlywayMigrationTest.java` — 테스트 작성 패턴 참고
- `/src/test/resources/application.yml` — `ddl-auto: validate`가 걸려 있다
- `/build.gradle.kts` — 사용 가능한 의존성

## 작업

`V1__init.sql`에 정의된 4개 테이블에 대응하는 JPA 엔티티와 리포지토리를 만든다.

### 패키지 배치

| 파일 | 경로 |
|---|---|
| `Subscriber`, `SubscriberStatus`, `SubscriberRepository` | `com.example.ainewsdigest.subscription` |
| `Digest`, `DigestItem`, `DigestStatus`, `DigestRepository` | `com.example.ainewsdigest.digest` |
| `DeliveryLog`, `DeliveryStatus`, `DeliveryLogRepository` | `com.example.ainewsdigest.delivery` |

### 시그니처 수준 지시

```java
// subscription/SubscriberStatus.java
public enum SubscriberStatus { ACTIVE, UNSUBSCRIBED }

// subscription/Subscriber.java  — table: subscriber
// 필드: id, chatId, status, source, subscribedAt, unsubscribedAt
// 도메인 동작을 엔티티에 둔다 (setter 남발 금지):
//   static Subscriber subscribe(long chatId, String source, Instant now)
//   void resubscribe(Instant now)          // UNSUBSCRIBED -> ACTIVE
//   void unsubscribe(Instant now)          // -> UNSUBSCRIBED
//   boolean isActive()
//
// 주의: 스키마의 consecutive_failures 컬럼은 매핑하지 않는다. MVP 검수에서
// 자동 해지 카운터를 제거했다 (죽은 구독자는 step 9의 403 즉시 해지가 처리한다).
// V1은 수정 금지지만 not null default 0이라 INSERT에 무해하고,
// ddl-auto: validate는 엔티티에 없는 DB 컬럼을 문제 삼지 않는다.

// digest/DigestStatus.java
public enum DigestStatus { PENDING, SENT, EMPTY, FAILED }

// digest/Digest.java  — table: digest
// 필드: id, digestDate(LocalDate), status, messageText, generatedAt, sentAt
//      items(List<DigestItem>, @OneToMany(mappedBy="digest", cascade=ALL, orphanRemoval=true))
//      items에 @OrderBy("position ASC")를 반드시 붙인다.
//        이유: 없으면 DB가 돌려주는 순서 그대로다. 순서 보장이 없어 아카이브 화면의
//              1·2·3번이 뒤섞일 수 있고, 재현되지 않아 찾기 어려운 버그가 된다.
//   static Digest pending(LocalDate date, String messageText, Instant now)
//   static Digest empty(LocalDate date, String messageText, Instant now)
//   void addItem(DigestItem item)          // 양방향 연관관계를 여기서 맞춘다
//   void markSent(Instant now)
//     -> PENDING이면 status를 SENT로 바꾸고 sentAt = now
//     -> EMPTY이면 status를 EMPTY로 유지한 채 sentAt = now 만 채운다
//        이유: "그날 뉴스가 없었다"는 정보가 발송과 함께 사라지면 안 된다.
//              ARCHITECTURE.md "다이제스트 상태 규칙" / ADR-014
//     -> 호출 여부는 발송 결과를 아는 step 9가 판단한다. 엔티티 안에서 조건을 따지지 마라
//        (ADR-018: 전원 실패면 markSent를 아예 호출하지 않는다)
//   boolean isSent()                       // sentAt != null

// digest/DigestItem.java  — table: digest_item
// 필드: id, digest(@ManyToOne(fetch=LAZY)), position, titleKo, summaryKo,
//      sourceUrl, normalizedUrl, sourceDomain, score

// delivery/DeliveryStatus.java
public enum DeliveryStatus { SUCCESS, FAILED }

// delivery/DeliveryLog.java — table: delivery_log
// 필드: id, digestId(Long), subscriberId(Long), status, errorCode, attemptedAt
//   -> DeliveryLog는 다른 애그리거트를 @ManyToOne으로 참조하지 말고 ID(Long)만 들고 있어라.
//      이유: 발송은 구독자 단위로 트랜잭션을 분리하므로 엔티티 참조는 불필요한 로딩과
//            영속성 컨텍스트 결합을 만든다.
```

### 리포지토리

```java
public interface SubscriberRepository extends JpaRepository<Subscriber, Long> {
    Optional<Subscriber> findByChatId(long chatId);
    List<Subscriber> findAllByStatus(SubscriberStatus status);
}

public interface DigestRepository extends JpaRepository<Digest, Long> {
    Optional<Digest> findByDigestDate(LocalDate date);
    boolean existsByDigestDate(LocalDate date);
    // 최근 N일간 발송된 항목의 normalizedUrl 집합 (중복 제거용)
    @Query("...")
    List<String> findNormalizedUrlsSince(@Param("since") LocalDate since);
    // 최근 N일간 발송된 항목의 한글 제목 (LLM 선별 프롬프트에 중복 배제용으로 전달)
    @Query("...")
    List<String> findTitlesSince(@Param("since") LocalDate since);
}

public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, Long> {
    // 발송 재개용: 이 다이제스트에서 이미 발송에 성공한 구독자 ID 집합
    @Query("...")
    List<Long> findSubscriberIdsByDigestIdAndStatus(@Param("digestId") Long digestId,
                                                    @Param("status") DeliveryStatus status);
}
```

### 핵심 규칙 (반드시 지킬 것)

- **Enum은 반드시 `@Enumerated(EnumType.STRING)`으로 매핑한다.** ORDINAL은 enum 상수 순서를 바꾸는 순간 기존 데이터의 의미가 조용히 뒤바뀐다
- **다이제스트의 발송 여부는 `status`가 아니라 `sentAt`으로 판정한다.** `status`는 콘텐츠 성격, `sentAt`은 발송 여부다. 이 규칙을 step 8·9·10이 그대로 따른다 (ARCHITECTURE.md "다이제스트 상태 규칙")
- `LocalDate digestDate`는 `date`, `Instant`는 `timestamptz` 컬럼에 대응한다
- 시간은 전부 `Instant`를 쓴다. `LocalDateTime`을 쓰지 마라 — 타임존 정보가 사라진다
- 엔티티에 `@Transactional`을 붙이지 마라
- 공개 setter를 만들지 마라. 상태 변경은 위에 명시한 도메인 메서드로만 한다
- JPA용 기본 생성자는 `protected`로 둔다

## 테스트

`src/test/java/com/example/ainewsdigest/` 하위 각 도메인 패키지에 `@DataJpaTest` 테스트를 작성한다.
`@DataJpaTest`에 `@Import(TestcontainersConfig.class)`를 반드시 붙여야 DB가 붙는다.

최소 검증 항목:
1. `Subscriber` 저장 후 `findByChatId`로 조회된다
2. 같은 `chatId`로 두 번 저장하면 제약 위반 예외가 발생한다
3. `Digest`에 `DigestItem`을 3개 담아 저장하면 cascade로 함께 저장되고, **`position` 오름차순으로 조회된다** (역순으로 add한 뒤 다시 읽어 검증한다)
4. **같은 `digestDate`로 `Digest`를 두 번 저장하면 제약 위반 예외가 발생한다** (하루 1회 발송 멱등성)
5. `findNormalizedUrlsSince` / `findTitlesSince`가 기간 필터링을 올바르게 한다
6. `Subscriber.unsubscribe()` 후 `findAllByStatus(ACTIVE)`에 안 잡힌다
7. `markSent()`가 PENDING은 SENT로 바꾸고, **EMPTY는 status를 EMPTY로 유지한 채 `sentAt`만 채운다**
8. `findSubscriberIdsByDigestIdAndStatus`가 해당 다이제스트의 SUCCESS 구독자 ID만 돌려준다 (다른 다이제스트·FAILED 로그는 섞이지 않는다)

## Acceptance Criteria

```bash
./gradlew build   # Git Bash 기준. PowerShell/cmd에서는 gradlew.bat build
```

`ddl-auto: validate`가 걸려 있으므로, 엔티티 매핑이 `V1__init.sql`과 하나라도 어긋나면 컨텍스트 로딩이 실패한다. 빌드가 통과했다면 매핑이 스키마와 일치한다는 뜻이다.

## 검증 절차

1. 위 AC 커맨드를 실행한다. (Docker Desktop이 실행 중이어야 한다 — Testcontainers 사용)
2. 아키텍처 체크리스트를 확인한다:
   - ARCHITECTURE.md 디렉토리 구조를 따르는가? (도메인별 패키지)
   - ADR 기술 스택을 벗어나지 않았는가?
   - CLAUDE.md CRITICAL 규칙을 위반하지 않았는가?
3. 결과에 따라 `phases/0-mvp/index.json`의 step 0을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `V1__init.sql`을 수정하지 마라. 이유: 스키마는 이미 실제 PostgreSQL에서 검증됐다. 엔티티를 스키마에 맞춰야지 그 반대가 아니다. 매핑이 안 맞으면 엔티티를 고쳐라
- `Digest.markFailed()`를 만들지 마라. 이유: `FAILED`는 스키마의 예약값이고 MVP 흐름에는 저장 경로가 없다(생성 실패 시 예외를 전파하고 행을 만들지 않는다). FAILED로 저장하면 `digest_date` UNIQUE와 step 6의 멱등성 체크에 걸려 그날의 수동 재실행이 영구히 막힌다
- 새 마이그레이션 파일(`V2__*.sql`)을 만들지 마라. 이유: 이 step의 범위는 기존 스키마 매핑이다
- Lombok을 추가하지 마라. 이유: 의존성에 없다. 생성자와 접근자를 직접 작성하라
- 서비스·컨트롤러 클래스를 만들지 마라. 이유: 이 step은 영속성 계층만 다룬다
- `src/test/resources/application.yml`의 `ddl-auto: validate`를 `update`나 `create-drop`으로 바꾸지 마라. 이유: 그 순간 스키마 검증이 무력화되어 이 step의 AC가 의미를 잃는다
- 기존 테스트를 깨뜨리지 마라
