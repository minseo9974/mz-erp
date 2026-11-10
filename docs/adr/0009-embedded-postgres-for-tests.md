# ADR-0009: 통합 테스트 DB는 Docker 없이 띄우는 임베디드 PostgreSQL로 한다

- 상태: 채택 (2026-11-09) — 설계서의 "Testcontainers"를 대체

## 맥락
통합 테스트는 H2 같은 흉내 DB가 아니라 **운영과 같은 PostgreSQL 16**에서 돌아야 한다.
2단계부터 지연 제약 트리거(ADR-0006), CHECK 제약, 윈도 함수처럼 PostgreSQL 고유 기능을 테스트하기 때문이다.
처음 계획은 Testcontainers였지만 개발 PC(Windows)에 Docker가 없다.

## 결정
- `io.zonky.test:embedded-postgres` + PostgreSQL 16 바이너리(`embedded-postgres-binaries-bom:16.15.0`)를 테스트 의존성으로 쓴다
- 테스트 JVM당 1번만 띄우고 모든 통합 테스트가 공유 (`support/IntegrationTest`)
- Flyway 마이그레이션이 그대로 적용되므로 스키마·제약은 운영과 같다
- 테스트끼리 데이터를 지우지 않고, 회사·사용자를 매번 새로 만들어 격리한다 (모든 데이터가 회사 단위라 가능)

## 결과
- 좋은 점: Docker 없이 Windows·Linux(CI) 어디서나 같은 테스트가 돈다. 컨테이너 기동보다 빠르다
- 감수할 점: PostgreSQL 확장(extension) 일부는 바이너리에 없을 수 있다. 필요해지면 그때 Testcontainers를 CI 전용으로 추가 검토
- Docker는 로컬 개발 필수가 아니라 **배포용**(docker-compose, CI 이미지 빌드)으로만 쓴다
