# MZ-ERP

전표 입력부터 장부, 결산, 재무제표, 부가세, 차기이월까지 **회계 흐름 전체를 직접 구현하는 회계 ERP**입니다.
전산회계 1급 실무 범위(제조업 법인)를 기준으로 만들고, 결과 재무제표가 수기 계산 정답과 일치하는지 테스트로 검증합니다.

> 상태: **1단계 — 기반 구축** 진행 중 · 0단계(설계) 완료

[![CI](https://github.com/minseo9974/mz-erp/actions/workflows/ci.yml/badge.svg)](https://github.com/minseo9974/mz-erp/actions/workflows/ci.yml)

## 지금 되는 것

- 세션 로그인 + CSRF, 회사별 역할(WRITER / APPROVER / ADMIN)
- 회사 생성 시 표준 계정과목표(94개) 자동 복사, 계정·거래처 검색·등록·수정, 회계연도(12개월 기간) 생성
- 화면: 로그인, 회사 선택·생성, 계정과목(그리드·검색·관리자 수정), 거래처(등록·수정), 회계연도
- 테스트: 백엔드 통합 테스트 31개 (실제 PostgreSQL 16), 프론트 단위 테스트 25개, 모듈 의존 방향 검사(ArchUnit)
- CI/CD: PR마다 백엔드·프론트·Docker 이미지·시나리오 정답 검사, main 병합 시 arm64 이미지를 GHCR에 배포

```bash
cd backend && ./gradlew bootTestRun     # DB 설치 없이 백엔드 실행
cd frontend && npm install && npm run dev   # http://localhost:5173 (admin / admin1234)
```

자세한 실행 방법은 [docs/dev-setup.md](docs/dev-setup.md).

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
| [docs/dev-setup.md](docs/dev-setup.md) | 개발 환경·실행 방법 |
| [docs/deploy.md](docs/deploy.md) | 배포: Oracle Cloud 무료 VM + GitHub Actions CD |
| [infra/server/](infra/server/) | 운영 서버 구성 (Docker Compose, Caddy, 준비 스크립트) |
| [backend/](backend/) | Spring Boot 백엔드 |
| [frontend/](frontend/) | React 프론트엔드 |
| [docs/accounting-notes/](docs/accounting-notes/) | 기능별 회계 규칙 노트 |

## 기술 스택

| 영역 | 사용 중 | 예정 |
| --- | --- | --- |
| 백엔드 | Java 21 · Spring Boot 4.1 · Spring Security 7 · JPA(Hibernate 7) · Flyway | MyBatis (집계 조회, 3단계) |
| DB | PostgreSQL 16 | |
| 테스트 | JUnit 5 · 임베디드 PostgreSQL 16 · ArchUnit · Vitest · Testing Library | Playwright, k6 |
| 프론트 | React 19 · TypeScript 7 · Vite 8 · React Router 7 · TanStack Query 5 · AG Grid 36 | OpenAPI 타입 자동 생성 |
| 인프라 | GitHub Actions · Docker · Caddy · GHCR | 클라우드 VM 배포 (서버 준비 중) |
