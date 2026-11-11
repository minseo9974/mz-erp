# API 명세 (초안)

REST + JSON. 이 문서는 0단계 설계용 초안이고, 1단계부터는 springdoc-openapi가 만든 Swagger 문서가 기준이 됩니다.
예시 금액은 [시나리오 회사](../scenario/company.md) 거래를 그대로 씁니다.

## 공통 규칙

| 항목 | 규칙 |
| --- | --- |
| 기본 경로 | `/api/v1/companies/{companyId}/…` — 모든 업무 API는 회사 단위 |
| 인증 | 세션 쿠키 + CSRF 토큰 ([ADR-0008](../adr/0008-session-auth.md)) |
| 날짜 | ISO 8601 `2026-05-06` |
| 금액 | JSON 정수(원). **최대 9,007,199,254,740,991** (JavaScript `Number.MAX_SAFE_INTEGER`) — DB는 `NUMERIC(18,0)`이지만 브라우저가 정확히 다룰 수 있는 범위로 API에서 제한 |
| 코드 | 계정·거래처는 내부 id 대신 **코드**로 주고받음 (`"accountCode": "108"`) — 화면과 엑셀 업로드가 코드 기반이라서 |
| 목록 | `?page=0&size=50&sort=entryDate,asc` · 응답 `{ "content": [...], "page": 0, "size": 50, "totalElements": 38 }` |
| 동시 수정 | 수정 요청에 `version` 필수. 다르면 409 `VERSION_CONFLICT` |
| 오류 | RFC 7807 Problem Details + 업무 오류 코드 (아래) |

### 오류 응답

```json
{
  "type": "urn:mzerp:error:JOURNAL_UNBALANCED",
  "title": "차변과 대변 합계가 다릅니다",
  "status": 422,
  "code": "JOURNAL_UNBALANCED",
  "detail": "차변 1,100,000 / 대변 1,000,000 (차액 100,000)",
  "errors": [{ "field": "lines", "message": "차액 100,000" }]
}
```

| 코드 | HTTP | 언제 |
| --- | --- | --- |
| `JOURNAL_UNBALANCED` | 422 | 차변 합계 ≠ 대변 합계 |
| `JOURNAL_TOO_FEW_LINES` | 422 | 라인 2줄 미만 |
| `LINE_AMOUNT_INVALID` | 422 | 라인에 차변·대변이 둘 다 있거나 둘 다 0 |
| `PARTNER_REQUIRED` | 422 | 거래처 필수 계정인데 거래처 없음 |
| `ACCOUNT_NOT_POSTABLE` | 422 | 비활성 계정, 시스템 계정(400 손익) |
| `VAT_AMOUNT_MISMATCH` | 422 | 과세 세액 ≠ 공급가액 × 10% (절사), 영세·면세인데 세액 있음 |
| `PERIOD_CLOSED` | 409 | 마감된 기간에 전표 생성·전기 |
| `ENTRY_IMMUTABLE` | 409 | POSTED 전표 수정·삭제 |
| `ENTRY_ALREADY_REVERSED` | 409 | 이미 역분개된 전표를 또 역분개 |
| `REVERSAL_DATE_INVALID` | 422 | 역분개일이 원전표일보다 빠름 |
| `OPENING_NOT_FIRST_YEAR` | 422 | 첫 회계연도가 아닌데 기초잔액을 직접 입력 |
| `VERSION_CONFLICT` | 409 | 다른 사람이 먼저 수정 |
| `FORBIDDEN_ROLE` | 403 | 역할 권한 부족 (예: WRITER가 전기) |

## 1. 기준정보

| 메서드 | 경로 | 설명 | 단계 |
| --- | --- | --- | --- |
| GET | `/accounts?q=외상&category=ASSET` | 계정 검색 (화면의 코드 도움 팝업) | 1 |
| POST / PATCH | `/accounts`, `/accounts/{code}` | 계정 추가·수정 (사용 중인 계정은 분류·정상잔액 변경 불가) | 1 |
| GET | `/partners?q=한빛&type=CUSTOMER` | 거래처 검색 | 1 |
| POST / PATCH | `/partners`, `/partners/{code}` | 거래처 등록·수정 | 1 |
| GET | `/fiscal-years` | 회계연도 목록과 월별 마감 상태 | 1 |
| POST | `/fiscal-years` | 회계연도 생성 (12개 기간 자동 생성) | 1 |
| GET | `/fiscal-years/{year}/opening-balance` | 전기분 재무상태표 조회. `{year}`는 회계연도가 시작하는 해. `entry`가 null이면 아직 입력 전 | 2 |
| PUT | `/fiscal-years/{year}/opening-balance` | 전기분 재무상태표 저장 `{ "lines": [...] }` → 임시 OPENING 전표 생성·교체 (첫 해만, 재무상태표 계정만). 전기·삭제·역분개는 일반전표 API로 | 2 |

## 2. 전표

### 일반전표

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/journal-entries?from=2026-01-01&to=2026-01-31&status=POSTED&accountCode=108&partnerCode=00101&type=GENERAL` | 전표 목록 |
| GET | `/journal-entries/{id}` | 전표 상세 (라인, 부가세 정보, 역분개 연결) |
| POST | `/journal-entries` | 일반전표 생성 → DRAFT |
| PUT | `/journal-entries/{id}` | DRAFT 전표 수정 (`version` 필수) |
| DELETE | `/journal-entries/{id}` | DRAFT 전표 삭제 |
| POST | `/journal-entries/{id}/post` | 전기 (DRAFT → POSTED). APPROVER 이상 |
| POST | `/journal-entries/post` | 일괄 전기 `{ "ids": [..] }` — 하나라도 실패하면 전부 롤백 |
| POST | `/journal-entries/{id}/reverse` | 역분개 전표 생성 `{ "reversalDate": "2026-07-01", "reason": "계정 오류" }` |

**생성 요청** — 시나리오 #6 받을어음 만기

```json
POST /api/v1/companies/1/journal-entries
{
  "entryDate": "2026-03-10",
  "description": "(주)한빛전자 받을어음 만기 보통예금 입금",
  "lines": [
    { "side": "DEBIT",  "accountCode": "103", "amount": 5000000 },
    { "side": "CREDIT", "accountCode": "110", "partnerCode": "00101", "amount": 5000000 }
  ]
}
```

**응답** `201 Created`

```json
{
  "id": 1042,
  "entryDate": "2026-03-10",
  "entryNo": 1,
  "entryType": "GENERAL",
  "status": "DRAFT",
  "version": 0,
  "debitTotal": 5000000,
  "creditTotal": 5000000,
  "lines": [
    { "lineNo": 1, "side": "DEBIT", "accountCode": "103", "accountName": "보통예금", "amount": 5000000 },
    { "lineNo": 2, "side": "CREDIT", "accountCode": "110", "accountName": "받을어음",
      "partnerCode": "00101", "partnerName": "(주)한빛전자", "amount": 5000000 }
  ]
}
```

> **화면의 출금·입금 구분**: 전산회계 프로그램처럼 화면에서 "출금"을 고르면 대변 현금(101) 라인을, "입금"을 고르면
> 차변 현금 라인을 자동으로 채웁니다. API는 항상 완성된 차·대 라인만 받습니다 — 편의 기능은 화면 책임.

### 매입매출전표

부가세 라인(255 / 135)과 공급가 라인은 **서버가 만듭니다.** 사용자는 유형, 공급가액, 거래처, 상대 계정, 결제 방법만 정합니다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/sales-purchase-entries/preview` | 저장하지 않고 생성될 분개만 계산 (입력 중 미리보기) |
| POST | `/sales-purchase-entries` | 매입매출전표 생성 → DRAFT |
| PUT | `/sales-purchase-entries/{id}` | DRAFT 수정 |

**요청** — 시나리오 #12 새솔테크 제품 매출 (어음 5,000,000 + 잔액 외상)

```json
POST /api/v1/companies/1/sales-purchase-entries
{
  "entryDate": "2026-05-06",
  "description": "(주)새솔테크 제품 매출",
  "vat": {
    "direction": "SALES",
    "type": "11",
    "partnerCode": "00102",
    "supplyAmount": 25000000,
    "electronic": true
  },
  "mainAccountCode": "404",
  "settlements": [
    { "accountCode": "110", "partnerCode": "00102", "amount": 5000000 },
    { "accountCode": "108", "partnerCode": "00102", "amount": 22500000 }
  ]
}
```

서버 처리:
1. `taxAmount` 생략 시 과세 유형이면 공급가액 × 10% 절사 = 2,500,000, 영세·면세면 0
2. 매출이면 (대) 404 제품매출 25,000,000 + (대) 255 부가세예수금 2,500,000 생성
3. `settlements` 합계 = 공급가 + 세액(27,500,000)인지 검증 → 차변 라인으로 생성
4. 불공(54)이면 부가세대급금 없이 세액을 `mainAccountCode`(예: 813)에 합산

**preview 응답** (`/preview`, 저장 안 함)

```json
{
  "taxAmount": 2500000,
  "lines": [
    { "side": "DEBIT",  "accountCode": "110", "partnerCode": "00102", "amount": 5000000 },
    { "side": "DEBIT",  "accountCode": "108", "partnerCode": "00102", "amount": 22500000 },
    { "side": "CREDIT", "accountCode": "404", "amount": 25000000 },
    { "side": "CREDIT", "accountCode": "255", "amount": 2500000 }
  ],
  "balanced": true
}
```

## 3. 장부 (읽기 전용, MyBatis 집계)

| 메서드 | 경로 | 설명 | 전산회계 메뉴 |
| --- | --- | --- | --- |
| GET | `/ledgers/account?accountCode=108&from=2026-01-01&to=2026-12-31` | 계정별원장: 전기이월 → 일자별 라인 → 잔액, 월계·누계 | 계정별원장 |
| GET | `/ledgers/partner?accountCode=108&partnerCode=00101&from=…&to=…` | 거래처원장 | 거래처원장 |
| GET | `/ledgers/partner-balances?accountCode=108&asOf=2026-12-31` | 거래처별 잔액 목록 | 거래처원장(잔액) |
| GET | `/ledgers/cash-book?from=…&to=…` | 현금출납장 (101) | 현금출납장 |
| GET | `/reports/daily?date=2026-05-06` | 일계표 | 일계표 |
| GET | `/reports/monthly?month=2026-05` | 월계표 | 월계표 |
| GET | `/reports/trial-balance?asOf=2026-12-31&includeClosing=true` | 합계잔액시산표 | 합계잔액시산표 |

**계정별원장 응답 모양**

```json
{
  "account": { "code": "108", "name": "외상매출금", "normalBalance": "DEBIT" },
  "openingBalance": 20000000,
  "rows": [
    { "date": "2026-02-05", "entryId": 1004, "entryNo": 1, "description": "(주)한빛전자 제품 외상 매출",
      "partnerName": "(주)한빛전자", "debit": 33000000, "credit": 0, "balance": 53000000 }
  ],
  "monthlyTotals": [{ "month": "2026-02", "debit": 33000000, "credit": 12000000 }],
  "closingBalance": 46500000
}
```

## 4. 부가세

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/vat/ledger?year=2026&term=1&direction=SALES&type=11` | 매입매출장 |
| GET | `/vat/return?year=2026&term=1` | 부가세 신고서 집계 ([정답 6번](../scenario/answers-2026.md)의 란 구성) |
| GET | `/vat/invoice-summary?year=2026&term=1&direction=PURCHASE` | 세금계산서 합계표 (거래처별 매수·공급가·세액) |
| POST | `/vat/settlement?year=2026&term=1` | 부가세 정리 전표 생성 (255 / 135 / 261) → DRAFT |

## 5. 결산·마감

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/closing/2026/worksheet` | 결산자료: 원재료·제품 장부 흐름, 감가상각 예정액, 대손충당금 설정 예정액, 선납세금 잔액 |
| POST | `/closing/2026/auto-entries` | 결산 자동분개 생성 → DRAFT (기존 자동분개 DRAFT는 교체) |
| POST | `/periods/2026-12/close` | 월 마감. DRAFT 전표가 남아 있으면 거부 |
| POST | `/periods/2026-12/reopen` | 마감 취소 `{ "reason": "…" }` — ADMIN, 감사 로그 필수 |
| POST | `/fiscal-years/2026/carry-forward` | 연도 마감: 손익 대체 + 다음 연도 OPENING 전표 생성 |

**결산 자동분개 요청** — 시나리오 결산자료

```json
POST /api/v1/companies/1/closing/2026/auto-entries
{
  "endingRawMaterial": 3000000,
  "endingWorkInProcess": 0,
  "endingFinishedGoods": 10000000,
  "badDebtRatePercent": 1,
  "incomeTax": 900000,
  "includeDepreciation": true
}
```

응답: 생성된 결산 전표 목록 (원재료비 29,000,000 → 제품 대체 70,900,000 → 매출원가 68,900,000, 감가상각, 대손충당금, 법인세). 원가 흐름은 [ADR-0007](../adr/0007-manufacturing-cost-flow.md).
선급비용·미지급비용 같은 수동 정리분개는 일반전표 API로 입력합니다.

## 6. 재무제표

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/statements/balance-sheet?year=2026&compare=true` | 재무상태표 (당기·전기 비교) |
| GET | `/statements/income-statement?year=2026&compare=true` | 손익계산서 |
| GET | `/statements/manufacturing-cost?year=2026` | 제조원가명세서 |
| GET | `/statements/retained-earnings?year=2026` | 이익잉여금처분계산서 |

응답은 화면과 엑셀·PDF가 같은 데이터를 쓰도록 **행 목록** 형태입니다.

```json
{
  "title": "손익계산서",
  "period": { "from": "2026-01-01", "to": "2026-12-31" },
  "rows": [
    { "level": 0, "label": "Ⅰ. 매출액", "current": 105000000, "prior": null },
    { "level": 1, "label": "제품매출", "accountCode": "404", "current": 105000000, "prior": null },
    { "level": 0, "label": "Ⅹ. 당기순이익", "current": 6285000, "prior": null, "emphasis": true }
  ]
}
```

## 7. 고정자산 · 관리 (6단계)

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET / POST / PATCH | `/fixed-assets` | 고정자산 등록 (취득일, 취득가, 내용연수, 상각방법, 계정) |
| GET | `/fixed-assets/depreciation?year=2026` | 자산별 당기 상각액·누계액 (결산 worksheet가 사용) |
| POST | `/fixed-assets/{id}/dispose` | 처분 → 처분손익 전표 DRAFT |
| GET | `/audit-logs?entityType=JOURNAL_ENTRY&entityId=1042` | 변경 이력 |
| GET / POST | `/users`, `/users/{id}/roles` | 사용자·역할 (WRITER / APPROVER / ADMIN) |

## 인증 (회사 경로 밖)

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/v1/auth/csrf` | CSRF 토큰 발급 |
| POST | `/api/v1/auth/login` | `{ "loginId", "password" }` → 세션 쿠키 |
| POST | `/api/v1/auth/logout` | 세션 종료 |
| GET | `/api/v1/me` | 내 정보와 접근 가능한 회사·역할 |

## 단계별 구현 범위

| 단계 | API |
| --- | --- |
| 1. 기반 | 인증, 기준정보(계정·거래처·회계연도) |
| 2. 전표 | 일반전표 전체, 역분개, 전기분 재무상태표 |
| 3. 장부 | 장부·시산표 |
| 4. 부가세 | 매입매출전표, 부가세 장부·신고서·정리 |
| 5. 결산 | 결산 worksheet·자동분개, 마감, 재무제표, 차기이월 |
| 6. 고정자산·권한 | 고정자산, 감사 로그, 사용자·역할 |
