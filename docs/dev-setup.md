# 개발 환경

## 필요한 것

| 도구 | 버전 | 비고 |
| --- | --- | --- |
| JDK | 21 | 예: IntelliJ가 받은 `~/.jdks/ms-21.0.10`. `JAVA_HOME`으로 지정 |
| Node.js | 22 | 프론트엔드 |
| Python | 3.11+ | 시나리오 정답 스크립트 (표준 라이브러리만 사용) |
| PostgreSQL | 선택 | 필요 없음 — 테스트와 로컬 실행 모두 임베디드 PostgreSQL 16을 씀 |
| Docker | 선택 | 배포용 이미지·compose. 로컬 개발에는 없어도 됨 |

## 로컬 실행 (가장 간단)

터미널 두 개를 엽니다.

```bash
# 1) 백엔드 — 임베디드 PostgreSQL과 함께 8080 포트로 뜸
cd backend
./gradlew bootTestRun
```

```bash
# 2) 프론트엔드 — 5173 포트, /api는 백엔드로 프록시
cd frontend
npm install
npm run dev
```

http://localhost:5173 에서 **개발용 계정 `admin` / `admin1234`** 로 로그인합니다.

- 개발 DB는 `backend/.local/pgdata`에 남아서 재시작해도 데이터가 유지됩니다. 지우면 초기화
- 개발용 계정은 테스트 소스(`LocalDevApplication`)에만 있고 운영 jar에는 들어가지 않습니다
- API 문서: http://localhost:8080/swagger-ui.html

## 테스트

```bash
cd backend && ./gradlew test          # 통합 테스트 (임베디드 PostgreSQL 16)
cd frontend && npx tsc -b && npm run lint && npm test && npm run build
py -I tools/scenario/compute_answers.py   # 시나리오 정답 재생성·검증
```

CI(GitHub Actions)도 같은 명령을 돌립니다. main은 보호되어 있어 PR의 CI가 모두 통과해야 병합됩니다.

## 작업 흐름

```bash
git checkout -b feat/<작업명>
# 작업 · 테스트
git push -u origin feat/<작업명>
gh pr create            # CI 통과 → 스쿼시 병합 → CD
```

## 로컬 PostgreSQL에 붙여 실행 (선택)

```sql
-- psql 관리자 계정으로 한 번만
create role mzerp login password 'mzerp';
create database mzerp owner mzerp;
```

```bash
cd backend
MZERP_ADMIN_LOGIN_ID=admin MZERP_ADMIN_PASSWORD=change-me ./gradlew bootRun
```

접속 정보 기본값은 `jdbc:postgresql://localhost:5432/mzerp`, `mzerp` / `mzerp`이고 `MZERP_DB_URL`, `MZERP_DB_USERNAME`, `MZERP_DB_PASSWORD`로 바꿉니다.

## API 직접 호출 (curl)

```bash
curl -c jar.txt http://localhost:8080/api/v1/auth/csrf                      # 1) CSRF 쿠키
TOKEN=$(grep XSRF-TOKEN jar.txt | awk '{print $7}')
curl -b jar.txt -c jar.txt -H "X-XSRF-TOKEN: $TOKEN" -H "Content-Type: application/json" \
  -d '{"loginId":"admin","password":"admin1234"}' http://localhost:8080/api/v1/auth/login   # 2) 로그인
```

> Windows Git Bash에서 `-d '...한글...'`로 보내면 한글이 UTF-8이 아닌 인코딩으로 넘어가 400이 납니다.
> 한글이 든 본문은 UTF-8 파일로 저장해 `--data-binary @body.json`으로 보내세요.

## Docker로 실행 (선택)

```bash
cp .env.example .env   # 비밀번호 채우기
docker compose up -d --build
```

백엔드와 DB만 띄웁니다(DB는 호스트 5433). 화면까지 포함한 운영 구성은 `infra/server/`와 [deploy.md](deploy.md)를 보세요.
