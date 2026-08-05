# UI 디자인 가이드

Spring MVC + Thymeleaf 서버 사이드 렌더링 기준. 스타일은 `src/main/resources/static/`의 순수 CSS로 작성한다.

## 디자인 원칙
1. **도구처럼 보여야 한다.** 마케팅 페이지가 아니라 매일 읽는 뉴스 아카이브다. 설득하려 들지 말고 내용을 먼저 보여준다.
2. **읽기 우선.** 이 사이트의 콘텐츠는 한글 텍스트다. 장식 요소가 본문 가독성을 해치면 장식을 버린다.
3. **다크모드 고정.** 라이트모드 토글을 만들지 않는다. 색상 변수는 하나의 팔레트만 정의한다.

## AI 슬롭 안티패턴 — 하지 마라
| 금지 사항 | 이유 |
|-----------|------|
| backdrop-filter: blur() | glass morphism은 AI 템플릿의 가장 흔한 징후 |
| gradient-text (배경 그라데이션 텍스트) | AI가 만든 SaaS 랜딩의 1번 특징 |
| "Powered by AI" 배지 | 기능이 아니라 장식. 사용자에게 가치 없음 |
| box-shadow 글로우 애니메이션 | 네온 글로우 = AI 슬롭 |
| 보라/인디고 브랜드 색상 | "AI = 보라색" 클리셰 |
| 모든 카드에 동일한 큰 border-radius | 균일한 둥근 모서리는 템플릿 느낌 |
| 배경 gradient orb (크게 블러 처리한 원형) | 모든 AI 랜딩 페이지에 있는 장식 |

## 색상

CSS 커스텀 프로퍼티로 정의하고, 개별 요소에 raw hex를 직접 쓰지 않는다.

### 배경
| 용도 | 변수 | 값 |
|------|------|------|
| 페이지 | `--color-bg-page` | `#0a0a0a` |
| 카드 | `--color-bg-card` | `#141414` |
| 경계선 | `--color-border` | `#262626` |

### 텍스트
| 용도 | 변수 | 값 |
|------|------|------|
| 주 텍스트 | `--color-text-primary` | `#fafafa` |
| 본문 | `--color-text-body` | `#d4d4d4` |
| 보조 | `--color-text-muted` | `#a3a3a3` |
| 비활성 | `--color-text-disabled` | `#737373` |

### 포인트 (1가지만)
| 용도 | 변수 | 값 |
|------|------|------|
| 강조·링크·CTA | `--color-accent` | `#f59e0b` |
| 강조 hover | `--color-accent-hover` | `#fbbf24` |

### 데이터/시맨틱 색상
| 용도 | 변수 | 값 |
|------|------|------|
| 발송 완료 | `--color-positive` | `#4ade80` |
| 발송 실패 | `--color-negative` | `#f87171` |
| 중립/기본 | `--color-neutral` | `#525252` |

## 컴포넌트
### 카드
```css
.card {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: 6px;
  padding: 20px 24px;
}
```

### 버튼
```css
.btn-primary {
  background: var(--color-accent);
  color: #0a0a0a;
  border: none;
  border-radius: 6px;
  padding: 10px 18px;
  font-weight: 600;
}
.btn-primary:hover { background: var(--color-accent-hover); }

.btn-text { color: var(--color-text-disabled); background: none; border: none; }
.btn-text:hover { color: var(--color-text-muted); }
```

### 다이제스트 항목
```css
.digest-item { padding: 16px 0; border-bottom: 1px solid var(--color-border); }
.digest-item:last-child { border-bottom: none; }
.digest-item__title { color: var(--color-text-primary); font-weight: 600; }
.digest-item__summary { color: var(--color-text-body); }
.digest-item__source { color: var(--color-text-muted); font-size: 13px; }
.digest-item__source a { color: var(--color-accent); text-decoration: none; }
.digest-item__source a:hover { text-decoration: underline; }
```

## Thymeleaf 템플릿 규약
- 공통 조각(헤더, 푸터, 레이아웃)은 `templates/fragments/`에 두고 `th:replace`로 조합한다
- 페이지 템플릿은 `templates/{도메인}/{화면}.html`에 배치한다 (예: `templates/digest/archive.html`)
- 인라인 `style` 속성을 쓰지 않는다. 모든 스타일은 `static/css/`의 클래스로 정의한다
- JavaScript는 쓰지 않는다. 이 사이트에 동적 동작이 필요한 화면은 없다

## 레이아웃
- 전체 너비: `max-width: 720px` (읽기 중심. 대시보드가 아니므로 넓히지 않는다)
- 정렬: 좌측 정렬 기본. 중앙 정렬은 페이지 컨테이너 자체에만 적용한다
- 간격: 요소 간 12~16px, 섹션 간 40px
- 모바일: 720px 미만에서 좌우 패딩 16px

## 타이포그래피
| 용도 | 스타일 |
|------|--------|
| 페이지 제목 | 28px / weight 700 / `--color-text-primary` |
| 날짜 헤더 | 14px / weight 600 / `--color-text-muted` / letter-spacing 0.02em |
| 항목 제목 | 17px / weight 600 / `--color-text-primary` / line-height 1.4 |
| 본문·요약 | 15px / line-height 1.75 / `--color-text-body` |
| 출처·메타 | 13px / `--color-text-muted` |

폰트: `system-ui, -apple-system, "Segoe UI", "Malgun Gothic", sans-serif`. 웹폰트를 로드하지 않는다(로딩 지연과 CLS를 만들 이유가 없다).

## 애니메이션
- 허용: 링크·버튼의 `color` / `background-color` 전환 `0.15s ease` 만
- 그 외 모든 애니메이션 금지 (fade-in, slide-up, scale, 스크롤 트리거 등 일체)

## 아이콘
- SVG 인라인, `stroke-width: 1.5`, `currentColor` 사용
- 아이콘 컨테이너(둥근 배경 박스)로 감싸지 않는다
- 꼭 필요한 곳에만 쓴다. 목록 항목마다 아이콘을 붙이지 않는다
