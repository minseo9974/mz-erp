# 개발 환경

## 필요한 것

| 도구 | 버전 | 비고 |
| --- | --- | --- |
| JDK | 21 | 예: IntelliJ가 받은 `~/.jdks/ms-21.0.10`. `JAVA_HOME`으로 지정 |
| PostgreSQL | 16 | 로컬 실행용. 테스트는 자체 임베디드 PostgreSQL을 띄우므로 필요 없음 |
| Python | 3.11+ | 시나리오 정답 스크립트 (표준 라이브러리만 사용) |
| Docker | 선택 | 배포용 이미지·compose. 로컬 개발에는 없어도 됨 |

## 테스트

```bash
cd backend
./gradlew test
```

처음 실행하면 Gradle과 라이브러리, PostgreSQL 16 바이너리를 내려받습니다. 테스트 리포트는 `backend/build/reports/tests/test/index.html`.

## 로컬 실행 (로컬 PostgreSQL 사용)

1. DB와 계정 만들기 (psql, 관리자 계정으로 한 번만)

```sql
create role mzerp login password 'mzerp';
create database mzerp owner mzerp;
```

2. 첫 관리자 계정을 환경변수로 주고 실행 (사용자가 한 명도 없을 때만 생성)

```bash
cd backend
MZERP_ADMIN_LOGIN_ID=admin MZERP_ADMIN_PASSWORD=change-me ./gradlew bootRun
```

DB 접속 정보 기본값은 `jdbc:postgresql://localhost:5432/mzerp`, `mzerp` / `mzerp`이고 `MZERP_DB_URL`, `MZERP_DB_USERNAME`, `MZERP_DB_PASSWORD`로 바꿀 수 있습니다.

3. 확인
- 헬스체크: http://localhost:8080/actuator/health
- API 문서: http://localhost:8080/swagger-ui.html

## API 호출 순서 (curl 예)

```bash
# 1) CSRF 쿠키 받기
curl -c jar.txt http://localhost:8080/api/v1/auth/csrf
# 2) 로그인 (쿠키의 XSRF-TOKEN 값을 헤더로)
TOKEN=$(grep XSRF-TOKEN jar.txt | awk '{print $7}')
curl -b jar.txt -c jar.txt -H "X-XSRF-TOKEN: $TOKEN" -H "Content-Type: application/json" \
  -d '{"loginId":"admin","password":"change-me"}' http://localhost:8080/api/v1/auth/login
# 3) 회사 만들기 → 표준 계정과목표 94개가 함께 생성됨
curl -b jar.txt -H "X-XSRF-TOKEN: $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"(주)엠제트전자","bizRegNo":"000-81-00001","industry":"제조업"}' http://localhost:8080/api/v1/companies
```

## Docker로 실행

```bash
cp .env.example .env   # 비밀번호 채우기
docker compose up -d --build
```

DB는 호스트 5433 포트로 노출됩니다 (로컬 PostgreSQL 5432와 겹치지 않게).
