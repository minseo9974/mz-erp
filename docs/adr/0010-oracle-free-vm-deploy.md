# ADR-0010: Oracle Cloud Always Free VM 1대에 Docker Compose로 배포하고, GitHub Actions가 SSH로 배포한다

- 상태: 채택 (2026-11-09)

## 맥락
배포 비용은 0원이어야 하고, 면접관이 언제 들어와도 바로 열려야 한다 (잠들었다 깨는 무료 호스팅은 첫 요청이 1분씩 걸림).
DB는 PostgreSQL 16이어야 한다 (트리거·CHECK 제약·집계 SQL).

2026-11 기준으로 확인한 무료 선택지:

| 선택지 | DB | 앱 | 문제 |
| --- | --- | --- | --- |
| **Oracle Always Free** | VM에서 직접 | A1 arm64 최대 2 OCPU / 12 GB | 가입 시 카드 인증, 유휴 회수 규칙, 2026-06에 한도 절반 축소 |
| AWS Free plan | RDS | EC2 | 신규 계정은 크레딧 기반으로 6개월(또는 크레딧 소진) 후 종료 — 상시 무료 아님 |
| Render + Neon/Supabase | 관리형 (0.5~1 GB, 유휴 시 정지) | 512 MB, 15분 유휴 후 잠듦 | Spring Boot에 메모리 부족, 콜드 스타트 1분, Render 무료 DB는 30일 후 삭제 |

## 결정
- Oracle Cloud Always Free, **VM.Standard.A1.Flex 2 OCPU / 6 GB**, Ubuntu 24.04 arm64
  - 홈 리전은 일본(오사카 또는 도쿄): 가입 시 한국 리전이 무료 계정 선택지에 없었고, 춘천은 문서상 무료 A1 생성 불가 리전
  - 메모리를 무료 한도(12 GB)보다 작게 잡는 이유: 유휴 회수 조건(7일간 CPU·네트워크·메모리 사용률 모두 20% 미만)에 걸리지 않도록 메모리 사용률을 20% 위로 유지
- 서버 안: Caddy(자동 HTTPS) + 백엔드 + PostgreSQL 16, Docker Compose
  - 설계서의 Nginx 대신 Caddy: Let's Encrypt 발급·갱신을 설정 몇 줄로 처리, 인증서 갱신 cron이 필요 없음
- 이미지: GitHub Actions **arm64 러너**(`ubuntu-24.04-arm`, 공개 저장소 무료)에서 빌드 → GHCR
- 배포: 배포 전용 SSH 키로 `deploy` 사용자 접속 → `docker compose pull && up -d` → 헬스체크
- 롤백: 이미지 태그(커밋 SHA)를 지정해 CD 수동 실행

## 결과
- 좋은 점: 0원, 항상 켜져 있음, 운영 구성이 저장소(`infra/server/`)에 코드로 남음, 롤백이 태그 하나
- 감수할 점
  - 서버 1대라 배포 중 수 초 중단, 서버 장애 시 서비스 중단 — 데모 용도로 수용
  - Oracle 정책 변경 위험 (2026-06처럼 예고 없이 바뀔 수 있음) → 서버 구성이 compose 하나라 다른 VM으로 옮기기 쉬움
  - DB 백업은 같은 서버 디스크 → 7단계에서 Object Storage(무료 20 GB)로 외부 보관 추가

## 참고
- [Oracle Always Free Resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm)
- [InfoQ: Oracle Quietly Halves Free Tier Ampere A1 Compute Limits (2026-07)](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/)
- [AWS Free Tier $200 credits, 6-month free plan](https://aws.amazon.com/about-aws/whats-new/2025/07/aws-free-tier-credits-month-free-plan)
