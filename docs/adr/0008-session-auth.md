# ADR-0008: 인증은 서버 세션 쿠키 + CSRF 토큰으로 한다

- 상태: 채택 (2026-11-09)

## 맥락
화면(React)과 API(Spring Boot)는 Nginx 뒤 **같은 도메인**에서 서비스된다 (Nginx가 정적 화면과 `/api`를 함께 받음).
외부 앱이나 다른 서비스가 API를 호출할 계획은 없다. 사용자 수는 데모 수준이다.

## 결정
- Spring Security 기본 폼 로그인을 JSON 로그인으로 바꿔 쓰고, 세션 쿠키(`HttpOnly`, `Secure`, `SameSite=Lax`)로 인증
- 상태를 바꾸는 요청(POST/PUT/PATCH/DELETE)은 CSRF 토큰 필수 (`/api/v1/auth/csrf`에서 발급, `X-XSRF-TOKEN` 헤더)
- 권한은 회사별 역할 `WRITER`(전표 작성) · `APPROVER`(전기·역분개) · `ADMIN`(마감 취소, 사용자 관리)
- 세션 저장소는 처음엔 메모리. 서버를 2대 이상으로 늘릴 때 Spring Session + PostgreSQL로 전환

## 검토한 대안
- **JWT (localStorage 저장)**: XSS에 토큰이 노출되고, 로그아웃·권한 회수가 어렵다. 같은 도메인 SPA에선 얻는 게 없음
- **JWT (HttpOnly 쿠키)**: 결국 쿠키 + CSRF 대응이 필요해 세션과 복잡도가 비슷한데 만료·폐기 관리만 늘어남

## 결과
- 좋은 점: 로그아웃·권한 변경이 즉시 반영, 토큰 탈취 위험 감소, 구현이 단순
- 감수할 점: 서버 확장 시 세션 공유 필요 (위 전환 계획). 모바일 앱 등 외부 클라이언트가 생기면 토큰 방식 재검토
