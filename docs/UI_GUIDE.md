# UI 디자인 가이드

Spring MVC + Thymeleaf 서버 사이드 렌더링 기준. 스타일은 `src/main/resources/static/`의 순수 CSS로 작성한다.

## 디자인 원칙
1. {원칙 1 — 예: "도구처럼 보여야 한다. 마케팅 페이지가 아니라 매일 쓰는 대시보드."}
2. {원칙 2}
3. {원칙 3}

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
| 페이지 | `--color-bg-page` | {예: #0a0a0a} |
| 카드 | `--color-bg-card` | {예: #141414} |

### 텍스트
| 용도 | 변수 | 값 |
|------|------|------|
| 주 텍스트 | `--color-text-primary` | {예: #ffffff} |
| 본문 | `--color-text-body` | {예: #d4d4d4} |
| 보조 | `--color-text-muted` | {예: #a3a3a3} |
| 비활성 | `--color-text-disabled` | {예: #737373} |

### 데이터/시맨틱 색상
| 용도 | 변수 | 값 |
|------|------|------|
| {긍정/성공} | `--color-positive` | {예: #22c55e} |
| {부정/에러} | `--color-negative` | {예: #ef4444} |
| {중립/기본} | `--color-neutral` | {예: #525252} |

## 컴포넌트
### 카드
```css
{예:
.card {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 24px;
}}
```

### 버튼
```css
{예:
.btn-primary { background: #fff; color: #000; border-radius: 8px; padding: 10px 16px; }
.btn-primary:hover { background: #e5e5e5; }
.btn-text { color: var(--color-text-disabled); background: none; border: none; }
.btn-text:hover { color: var(--color-text-muted); }}
```

### 입력 필드
```css
{예:
.input {
  background: #171717;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 12px 16px;
}}
```

## Thymeleaf 템플릿 규약
- 공통 조각(헤더, 푸터, 레이아웃)은 `templates/fragments/`에 두고 `th:replace`로 조합한다
- {페이지 템플릿 배치 규칙 (예: templates/{도메인}/{화면}.html)}
- {인라인 style 속성 금지 여부 등 추가 규칙}

## 레이아웃
- 전체 너비: {예: max-width 1024px}
- 정렬: {예: 좌측 정렬 기본. 중앙 정렬 금지}
- 간격: {예: 요소 간 12~16px, 섹션 간 32px}

## 타이포그래피
| 용도 | 스타일 |
|------|--------|
| 페이지 제목 | {예: 36px / font-weight 600 / var(--color-text-primary)} |
| 카드 제목 | {예: 14px / font-weight 500 / var(--color-text-muted)} |
| 본문 | {예: 14px / line-height 1.6 / var(--color-text-body)} |

## 애니메이션
- {허용할 애니메이션만 나열. 예: fade-in (0.4s), slide-up (0.5s)}
- {그 외 모든 애니메이션 금지}

## 아이콘
- {예: SVG 인라인, stroke-width 1.5}
- {예: 아이콘 컨테이너(둥근 배경 박스)로 감싸지 않는다}
