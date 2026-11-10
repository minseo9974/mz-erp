# 배포 (Oracle Cloud Always Free + GitHub Actions)

## 흐름

```
로컬 개발 → 브랜치 push → PR ──▶ CI (테스트 · 이미지 빌드 확인 · 시나리오 정답)
                                   │ 통과 후 main 병합
                                   ▼
                       CD: arm64 이미지 빌드 → GHCR 푸시
                                   ▼
                       SSH → 서버 /opt/mz-erp: docker compose pull && up -d
                                   ▼
                       https://<도메인>/actuator/health 확인 (실패하면 CD 빨간불)
```

서버 1대(Oracle A1, arm64)에 Caddy(HTTPS 자동) + Spring Boot + PostgreSQL 16을 Docker Compose로 올립니다. 결정 배경은 [ADR-0010](adr/0010-oracle-free-vm-deploy.md).

| 구성요소 | 위치 |
| --- | --- |
| 서버 구성 (compose, Caddyfile) | [infra/server/](../infra/server/) — CD가 배포마다 서버로 복사 |
| 서버 최초 준비 스크립트 | [infra/server/setup.sh](../infra/server/setup.sh) |
| 비밀값 | 서버의 `/opt/mz-erp/.env` (git에 없음) · GitHub Secrets |
| 이미지 | `ghcr.io/minseo9974/mz-erp-backend:<커밋 12자리>` |
| DB 백업 | 서버 `/opt/mz-erp/backups/`, 매일 03:00, 7일 보관 |

## 1. 직접 해야 하는 것 (계정·결제 인증은 본인만 가능)

### 1-1. Oracle Cloud 가입
- https://www.oracle.com/cloud/free/ 에서 가입. 카드 인증이 필요하지만 **Always Free 자원만 쓰면 과금되지 않습니다**
- **홈 리전**은 나중에 바꿀 수 없고 무료 자원은 홈 리전에서만 쓸 수 있습니다 → **`Japan Central (Osaka)` 또는 `Japan East (Tokyo)`**
  - 2026-11 가입 시점에 한국 리전(서울·춘천)이 무료 계정 홈 리전 목록에 나오지 않았습니다
  - 춘천은 Oracle 문서상으로도 무료 Ampere A1 서버를 만들 수 없는 예외 리전입니다
  - 일본 리전은 한국에서 응답 지연이 수십 ms 수준이라 데모 용도로 문제없습니다
- "Upgrade to Pay As You Go"는 누르지 마세요 (무료 계정으로 충분하고, 실수로 유료 자원을 만들 위험이 생김)
- Billing → Budgets에서 예산 1달러 알림을 걸어 두면 안심

### 1-2. VM 만들기 (Compute → Instances → Create instance)

| 항목 | 값 | 이유 |
| --- | --- | --- |
| Image | Canonical Ubuntu 24.04 (aarch64) | setup.sh 기준 |
| Shape | VM.Standard.A1.Flex · **2 OCPU / 6 GB** | 무료 한도는 2 OCPU / 12 GB(2026-06 축소). 아래 "유휴 회수" 참고 |
| Boot volume | 50 GB (기본값) | 무료 200 GB 안 |
| Networking | 공인 IPv4 자동 할당 | |
| SSH key | "Generate a key pair for me" → **개인키 다운로드** | `C:\Users\Lenovo\.ssh\mz-erp-oci.key`에 저장 |

- "Out of capacity" 오류가 나면 다른 가용 도메인(AD)을 고르거나 몇 시간 뒤 다시 시도
- **유휴 회수 규칙**: 7일 동안 CPU(95퍼센타일)·네트워크·메모리 사용률이 모두 20% 미만이면 Oracle이 회수할 수 있습니다. 12 GB로 만들면 앱+DB(약 2~3 GB)가 20%에 못 미치기 쉬워서, **6 GB로 만들어 메모리 사용률을 20% 위로** 유지합니다

### 1-3. 방화벽 (Networking → VCN → Security List → Ingress Rules)
- Source `0.0.0.0/0`, TCP, 포트 `80` 추가
- Source `0.0.0.0/0`, TCP, 포트 `443` 추가
- (서버 안쪽 iptables는 setup.sh가 엽니다 — Oracle Ubuntu는 두 군데 모두 열어야 함)

### 1-4. 무료 도메인
- https://www.duckdns.org 에 GitHub로 로그인 → 하위 도메인 생성 (예: `mz-erp`) → IP에 서버 공인 IP 입력
- `mz-erp.duckdns.org` → Caddy가 Let's Encrypt 인증서를 자동 발급

## 2. 서버 준비 (Claude와 함께, 처음 1번)

공인 IP와 개인키 경로를 알려주면 아래를 진행합니다. 각 단계 전에 확인을 받습니다.

1. 배포 전용 SSH 키 생성 (`~/.ssh/mz-erp-deploy`) — GitHub Actions가 서버에 접속할 때만 쓰는 키
2. `setup.sh` 실행: Docker 설치, `deploy` 사용자, 방화벽, 백업 cron
3. 서버에 `/opt/mz-erp/.env` 생성 (DB·관리자 비밀번호는 무작위로 만들고 서버에만 저장)
4. GitHub 저장소 설정
   - Secrets: `DEPLOY_HOST`(공인 IP), `DEPLOY_SSH_KEY`(배포 키 개인키), `DEPLOY_KNOWN_HOSTS`(서버 호스트 키)
   - Variables: `DEPLOY_DOMAIN`(예: `mz-erp.duckdns.org`)
   - Environment `production` 생성
5. 첫 배포 후 GHCR 패키지 `mz-erp-backend`를 **Public**으로 변경 (서버가 로그인 없이 pull하도록)
   - GitHub → Packages → mz-erp-backend → Package settings → Change visibility

## 운영

| 할 일 | 방법 |
| --- | --- |
| 배포 | main에 병합하면 자동 |
| 롤백 | Actions → CD → Run workflow → `image_tag`에 이전 커밋 12자리 |
| 로그 | `ssh deploy@<IP>` → `cd /opt/mz-erp && docker compose logs -f backend` |
| 백업 복원 | `docker compose exec -T db pg_restore -U mzerp -d mzerp --clean < backups/<파일>.dump` |

CD는 `DEPLOY_HOST` 시크릿이 없으면 배포 단계를 건너뛰고 이미지 빌드·푸시만 합니다. 그래서 서버를 만들기 전에도 main 병합이 실패하지 않습니다.
