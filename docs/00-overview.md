# MZ-ERP 개요

## 최종 목표

"거래 하나가 재무제표에 반영되기까지 제 코드로 설명할 수 있다" — 2027년 4월까지 배포된 회계 ERP로 증명한다.

| 목표 | 완료 기준 |
| --- | --- |
| 회계 흐름 전체 구현 | 전표 → 원장 → 시산표 → 결산 → 재무제표 → 차기이월을 한 회계연도 끝까지 |
| 전산회계 1급 수준 정확성 | 시나리오 회사의 결과 재무제표가 수기 계산 정답과 일치 |
| 품질 | 회계 불변식 테스트 전부 통과, 도메인 코드 커버리지 80% 이상 |
| 성능 | 전표 100만 건에서 월 합계잔액시산표 p95 2초 이내 (목표치) |
| 배포 | 공개 URL + 데모 계정, CI/CD 자동 배포 |
| 포트폴리오 | README, ADR, 기술 블로그 3편 이상 |

**하지 않을 것**: 실제 세무 신고·홈택스 연동, 급여·원천세, 재고 수불, 다중 통화, 연결재무제표. AI 기능(계정 추천, 이상 전표 탐지)은 2단계.

## 단계

| 단계 | 기간 (주 10시간 가정) | 완료 조건 |
| --- | --- | --- |
| 0. 설계 | 2026-11-16 ~ 11-29 | 시나리오 회사 거래 목록과 정답 재무제표 확정 |
| 1. 기반 구축 | 11-30 ~ 12-13 | PR 하나가 CI를 거쳐 운영 서버까지 자동 배포 |
| 2. 전표 엔진 | 12-14 ~ 2027-01-10 | 차대·불변·동시성 테스트 통과 |
| 3. 장부·시산표 | 01-11 ~ 01-31 | 시산표 불변식 + 잔액 재집계 일치 |
| 4. 매입매출·부가세 | 02-01 ~ 02-21 | 부가세 시나리오 정답 일치 |
| 5. 결산·재무제표·이월 | 02-22 ~ 03-21 | 1년치 골든 테스트 통과 |
| 6. 고정자산·채권·권한 | 03-22 ~ 04-04 | 상각·권한 거부 테스트 통과 |
| 7. 배포·성능·문서화 | 04-05 ~ 04-25 | 성능 목표, E2E 5개, 블로그 3편 |

## 0단계 체크리스트

- [x] 저장소 생성, 문서 구조
- [x] 계정과목 체계 초안 — [design/account-chart.md](design/account-chart.md)
- [x] ERD 초안 — [design/erd.md](design/erd.md)
- [x] 시나리오 회사 프로필 + 기초잔액 — [scenario/company.md](scenario/company.md)
- [x] ADR 0001~0008
- [x] 계정 시드 데이터 — [data/seed/accounts.csv](../data/seed/accounts.csv)
- [x] 시나리오 회사 1년치 거래 38건 + 결산정리 9건 — [data/scenario/mz-electronics-2026.toml](../data/scenario/mz-electronics-2026.toml)
- [x] 정답 생성·검증 스크립트 — [tools/scenario/compute_answers.py](../tools/scenario/compute_answers.py) → [scenario/answers-2026.md](scenario/answers-2026.md)
- [ ] 정답을 손으로 한 번 검산 (전산회계 공부 겸)
- [ ] 계정코드·매입매출 유형 번호를 실제 전산회계 프로그램(KcLep)과 대조
- [x] API 명세 초안 — [design/api.md](design/api.md)
- [x] 화면 목록과 와이어프레임 — [design/screens.md](design/screens.md)

## 1단계 체크리스트

- [x] 백엔드 뼈대: Spring Boot 4.1.2, Java 21, Gradle (설계서의 Boot 3.x는 지원 종료로 4.x로 변경)
- [x] DB 스키마 V1 (사용자·권한, 회사, 회계연도·기간, 계정, 거래처) — Flyway
- [x] 세션 로그인 + CSRF, 회사별 역할 — [ADR-0008](adr/0008-session-auth.md)
- [x] 기준정보 API: 회사, 계정과목(표준표 자동 복사), 거래처, 회계연도
- [x] 통합 테스트 31개 (임베디드 PostgreSQL 16) — [ADR-0009](adr/0009-embedded-postgres-for-tests.md), ArchUnit
- [x] Dockerfile, docker-compose, GitHub Actions CI (테스트 · 이미지 빌드 · 시나리오 정답 동기화)
- [x] 프론트엔드 뼈대: 로그인, 회사 선택, 계정과목·거래처·회계연도 화면 (React 19, 설계서의 18에서 변경) + 로컬 실행 모드(`bootTestRun`)
- [x] 배포 구성: Oracle Always Free VM + Caddy + GHCR + SSH 배포 — [deploy.md](deploy.md), [ADR-0010](adr/0010-oracle-free-vm-deploy.md)
- [ ] Oracle 가입·VM 생성(직접) → 서버 준비 → 첫 자동 배포
