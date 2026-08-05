# 아키텍처

## 디렉토리 구조
```
src/
├── main/
│   ├── java/com/example/ainewsdigest/
│   │   ├── domain/          # 도메인별 패키지 (controller, service, repository, entity, dto)
│   │   ├── global/          # 공통 설정, 예외 처리, 인터셉터
│   │   └── AinewsdigestApplication.java
│   └── resources/
│       ├── templates/       # Thymeleaf 템플릿
│       ├── static/          # CSS, JS, 이미지
│       ├── db/migration/    # Flyway 마이그레이션
│       └── application.yml  # 운영 설정 (PostgreSQL)
└── test/
    ├── java/com/example/ainewsdigest/
    └── resources/
        └── application.yml  # 테스트 설정 (인메모리 H2, Flyway 비활성)
```

## 패턴
{사용하는 디자인 패턴 (예: 계층형 아키텍처. 도메인별 패키지 구성. @Transactional은 Service 계층에만 부착)}

## 데이터 흐름
```
{데이터가 어떻게 흐르는지 (예:
HTTP 요청 → Controller → Service(@Transactional) → Repository → DB → DTO 변환 → Thymeleaf 렌더링
)}
```

## 트랜잭션 경계
{트랜잭션을 어디서 열고 닫는지 (예: Service 메서드 단위로 열고 닫는다. 조회 전용 메서드는 readOnly = true. Controller와 Repository에는 @Transactional을 붙이지 않는다)}
