# LoopCut - backend
개인구독·정기결제 통합관리 플랫폼

- 하단 탭: 홈 · 내 구독 · 분석 · 마이
- 별도 진입 화면: 스플래시/온보딩 · 로그인/회원가입 · 구독 등록 · 구독 상세 · 구독 수정 · 사용 기록 입력 · 해지 가이드 · 예산 설정 · 알림 설정
- MVP 우선 화면: 홈 · 내 구독 목록 · 구독 등록 · 구독 상세 · 분석/추천 · 해지 가이드

## 실행방법
- local
./gradlew.bat bootRun

- prod
./gradlew.bat bootRun --args='--spring.profiles.active=prod'

## 개발 접속 링크
http://localhost:8080/actuator/health

- swagger
Swagger UI: http://localhost:8080/swagger-ui/index.html (또는 /swagger-ui.html로 리다이렉트)
