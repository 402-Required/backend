# LoopCut - backend
개인구독·정기결제 통합관리 플랫폼

- 하단 탭: 홈 · 내 구독 · 분석 · 마이
- 별도 진입 화면: 스플래시/온보딩 · 로그인/회원가입 · 구독 등록 · 구독 상세 · 구독 수정 · 사용 기록 입력 · 해지 가이드 · 예산 설정 · 알림 설정
- MVP 우선 화면: 홈 · 내 구독 목록 · 구독 등록 · 구독 상세 · 분석/추천 · 해지 가이드

## 실행방법
프로필 구분 없이 Neon(PostgreSQL) 하나에 접속합니다.

./gradlew.bat bootRun

## Neon DB 접속방법
`.env`에 아래 값을 넣습니다. 값이 없으면 앱이 시작되지 않습니다.

| 변수 | 설명 |
|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | Neon JDBC 접속 정보 |
| `JWT_SECRET` | access token 서명 키 (32바이트 이상) |
| `JWT_EXPIRATION` | access token 만료(ms), 기본 1800000 (30분) |
| `GOOGLE_WEB_CLIENT_ID` | 구글 로그인 ID 토큰 검증용 **서버(Web) 클라이언트 ID** |
| `TERMS_VERSION` | 약관 버전, 기본 2026-11-01 |

DB 준비 순서 (Neon SQL Editor):
1. `sql/schema.sql` — 테이블·함수·카테고리 시드 (한 번만. 이미 있는 DB에 다시 실행하면 enum 생성에서 실패해요)
2. `sql/seeds/services.sql`, `sql/seeds/faqs.sql` — 서비스 카탈로그·FAQ 시드 (여러 번 실행해도 안전)

## API
- Base URL: `/api/v1`, 응답은 `{ "data": ... }` / 에러는 `{ "error": { "code", "message" } }`
- 인증: `Authorization: Bearer <accessToken>` (로그인·가입·카탈로그·FAQ 제외)
- 스케줄러: 결제 기록(매일 00:05), 결제 알림(10분마다), 정리(매일 04:00) — 모두 KST
- Play Console 계정 삭제 안내 페이지: `/account-deletion`

## 개발 접속 링크
http://localhost:8080/actuator/health

- swagger
Swagger UI: http://localhost:8080/swagger-ui/index.html (또는 /swagger-ui.html로 리다이렉트)
