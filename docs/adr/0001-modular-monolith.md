# ADR-0001: 모듈러 모놀리스로 만든다

- 상태: 채택 (2026-11-09)

## 맥락
회계 기능(전표, 장부, 부가세, 결산, 고정자산)은 한 트랜잭션 안에서 함께 바뀌는 일이 많다.
예: 매입매출전표 하나가 전표 라인, 부가세 정보, 월별 잔액을 동시에 바꾼다. 결산은 여러 모듈의 데이터를 한 번에 읽고 쓴다.

## 결정
하나의 Spring Boot 애플리케이션, 하나의 DB로 만든다. 모듈은 패키지로 나누고 의존 방향을 ArchUnit 테스트로 강제한다.

| 모듈 | 의존해도 되는 모듈 |
| --- | --- |
| master (기준정보) | 없음 |
| journal (전표) | master |
| ledger (장부) | master, journal |
| vat (부가세) | master, journal |
| asset (고정자산) | master, journal |
| closing (결산) | master, journal, ledger, asset |
| statement (재무제표) | master, ledger |

역방향 의존(예: journal → closing)은 금지. 결산이 전표를 만들 때는 journal 모듈의 공개 API를 호출한다.

## 결과
- 좋은 점: 한 트랜잭션으로 정합성 보장, 배포·운영 단순, 혼자 유지 가능
- 감수할 점: 모듈 경계를 어기기 쉬움 → ArchUnit으로 막음
- 면접 포인트: "마이크로서비스를 안 쓴 이유"를 회계 트랜잭션 특성으로 설명할 수 있다
