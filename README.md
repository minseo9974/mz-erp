# MZ-ERP

전표 입력부터 장부, 결산, 재무제표, 부가세, 차기이월까지 **회계 흐름 전체를 직접 구현하는 회계 ERP**입니다.
전산회계 1급 실무 범위(제조업 법인)를 기준으로 만들고, 결과 재무제표가 수기 계산 정답과 일치하는지 테스트로 검증합니다.

> 상태: **0단계 — 설계** 진행 중 (2026-11 ~)

## 문서

| 경로 | 내용 |
| --- | --- |
| [docs/00-overview.md](docs/00-overview.md) | 목표, 범위, 단계별 일정 요약 |
| [docs/design/account-chart.md](docs/design/account-chart.md) | 계정과목 체계 |
| [docs/design/erd.md](docs/design/erd.md) | 데이터 모델(ERD)과 무결성 규칙 |
| [docs/design/api.md](docs/design/api.md) | REST API 명세 초안 |
| [docs/design/screens.md](docs/design/screens.md) | 화면 목록과 와이어프레임 |
| [docs/scenario/company.md](docs/scenario/company.md) | 테스트용 시나리오 회사와 기초잔액 |
| [docs/scenario/answers-2026.md](docs/scenario/answers-2026.md) | 시나리오 정답 재무제표 (골든 테스트 기준) |
| [data/](data/) | 계정 시드, 시나리오 거래 원본(TOML) |
| [tools/scenario/](tools/scenario/) | 정답 생성·검증 스크립트 (`py tools/scenario/compute_answers.py`) |
| [docs/adr/](docs/adr/) | 설계 결정 기록(ADR) |
| [docs/accounting-notes/](docs/accounting-notes/) | 기능별 회계 규칙 노트 |

## 기술 스택 (예정)

Java 21 · Spring Boot 3 · JPA + MyBatis · PostgreSQL 16 · Flyway · React 18 + TypeScript + Vite · AG Grid · Testcontainers · GitHub Actions · Docker Compose
