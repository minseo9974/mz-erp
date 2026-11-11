# 같이 읽기 02 — 전표 엔진 (작성 · 전기 · 역분개 · 채번)

> 먼저 읽을 것: [회계 노트 01](../accounting-notes/01-double-entry.md), [회계 노트 02](../accounting-notes/02-journal-posting-reversal.md)
> 이 문서는 코드를 **읽는 순서대로** 따라가며, 회계 규칙이 코드 어디에서 지켜지고 어떤 테스트가 그걸 증명하는지 짚습니다.

## 0. 한눈에 보기

전표 하나가 저장되기까지:

```
POST /journal-entries
  │
  ▼ JournalController            요청 형식 검사 (@Valid: 날짜·라인 필수)
  ▼ JournalService.create
  │   ├─ resolveLines()          ① 계정 코드 → 계정 (사용 가능? 시스템 계정 아님? 거래처 필수?)
  │   ├─ FiscalCalendar          ② 그 날짜의 회계기간이 있고 열려 있나?
  │   ├─ EntryNumberGenerator    ③ 그 날짜의 다음 전표번호 (동시 입력에도 안 겹침)
  │   └─ JournalEntry.draft()    ④ 전표 한 장의 규칙 (2줄 이상, 양수, 차변 = 대변)
  ▼ JournalEntryRepository.save
  ▼ PostgreSQL                   ⑤ CHECK 제약 + 트리거 (앱을 우회한 경로까지 막는 마지막 방어선)
```

**설계의 뼈대 — 규칙을 어디에 둘까?**

| 규칙의 종류 | 어디서 | 이유 |
| --- | --- | --- |
| 전표 한 장만 보면 판단 가능 (차대 일치 등) | `JournalEntry` (도메인 객체) | DB 없이 단위 테스트 가능, 어떤 경로로 만들든 항상 적용 |
| 다른 데이터를 봐야 판단 가능 (계정 상태, 기간 마감) | `JournalService` | 저장소 조회가 필요 |
| 어떤 경로로도 절대 깨지면 안 됨 | DB 제약·트리거 | 배치·데이터 이관·직접 SQL까지 막음 |

## 1. 읽는 순서

| 순서 | 파일 | 볼 것 |
| --- | --- | --- |
| 1 | `common/money/Money.java` | 금액을 왜 `long` 래퍼로 감쌌나 |
| 2 | `journal/Side.java`, `EntryType.java`, `EntryStatus.java` | 용어 정리 |
| 3 | **`journal/JournalEntry.java`** | 핵심. `validate` → `draft` → `replace` → `post` → `reversalDrafts` |
| 4 | `journal/JournalLine.java` | 차변/대변 한쪽만, 역분개용 뒤집기 |
| 5 | **`journal/JournalService.java`** | `resolveLines`, `create`, `post`, `reverse` |
| 6 | `journal/EntryNumberGenerator.java` | 채번 SQL 한 문장 |
| 7 | `resources/db/migration/V2__journal.sql`, `V3__journal_guard_hardening.sql` | 테이블, 제약, 트리거 3개 + 보강(§5-3) |
| 8 | 테스트 4개 | 아래 §6 표 |

## 2. Money — 금액은 원 단위 정수

```java
public record Money(long won) {
    public Money plus(Money other) { return new Money(Math.addExact(won, other.won)); }
```

- `double`이면 `0.1 + 0.2 = 0.30000000000000004`. 회계에서 1원 오차는 시산표 불일치
- `Math.addExact`: long 범위를 넘으면 **조용히 음수가 되는 대신 예외** — 틀린 숫자보다 실패가 낫다
- DB는 `NUMERIC(18,0)`, 변환은 `MoneyConverter` (소수점이 들어오면 `longValueExact`가 예외)

## 3. JournalEntry — 전표 한 장의 규칙

### 3-1. `validate(lines)` — 회계 노트 02 §3을 그대로 코드로

```java
if (lines == null || lines.size() < 2) → JOURNAL_TOO_FEW_LINES
for (각 라인) {
    if (금액 <= 0 또는 너무 큼) → LINE_AMOUNT_INVALID
    차변이면 debit += 금액, 대변이면 credit += 금액
}
if (!debit.equals(credit)) → JOURNAL_UNBALANCED  ("차변 5,000,000원 / 대변 4,000,000원 (차액 1,000,000원)")
```

- 이 메서드는 `draft()`(생성)와 `replace()`(수정) **둘 다에서** 호출된다. 수정할 때 검증을 빼먹는 실수를 구조적으로 막음
- 오류 메시지에 차액까지 넣은 이유: 화면에서 사용자가 "어느 쪽이 얼마 모자란지" 바로 알게

### 3-2. 상태: `DRAFT → POSTED` 한 방향

```java
public void post(Long userId, Instant at) { requireDraft(); status = POSTED; ... }
public void requireDraft() { if (status != DRAFT) throw ENTRY_IMMUTABLE; }
```

- `replace()`, `post()`, 서비스의 `delete()`가 모두 `requireDraft()`를 먼저 부른다 → **전기된 전표를 바꾸는 코드 경로가 없다**
- 상태를 되돌리는 메서드(POSTED → DRAFT)는 아예 만들지 않았다. 정정은 역분개뿐 (ADR-0003)

### 3-3. 역분개: `reversalDrafts()` + `reversalOf()`

```java
List<LineDraft> reversalDrafts() {          // 원전표의 모든 라인을 차대만 뒤집기
    if (status != POSTED) throw ENTRY_NOT_POSTED;
    return getLines().stream().map(JournalLine::toReversedDraft).toList();
}
```

- 뒤집은 라인도 결국 `draft()`를 거치므로 **역분개 전표도 같은 차대 검증을 통과**한다 (원전표가 맞았다면 뒤집어도 맞음)
- 원전표 + 역분개 = 모든 계정의 변화량 0 → 회계 노트 02 §5의 표

## 4. JournalService — 다른 데이터가 필요한 규칙

### 4-1. `resolveLines()` — 코드를 id로 바꾸며 검사

| 검사 | 오류 |
| --- | --- |
| 계정 코드가 이 회사에 있나 | 404 |
| 시스템 계정(400 손익)이거나 사용 중지 | `ACCOUNT_NOT_POSTABLE` |
| 거래처 필수 계정인데 거래처 없음 | `PARTNER_REQUIRED` |
| 거래처 코드가 이 회사에 있나 | 404 |

- 계정은 회사 전체(약 94개)를 한 번에 읽어 Map으로 → 라인 수만큼 DB를 왕복하지 않음
- `if (code == "108")` 같은 하드코딩 없이 **계정 속성(`isRequiresPartner`, `isSystem`)으로 판단** (불변식 6)

### 4-2. `post()` — 기간을 다시 확인하는 이유
임시 전표를 3월 10일에 만들고, 3월이 마감된 뒤에 전기하려 할 수 있다. 그래서 전기 직전에 `requireOpenPeriod`를 한 번 더 부른다.
→ 테스트: `JournalApiTest > 전기 > 마감된 달에는 … 전기할 수도 없다`

### 4-3. `reverse()` — 왜 `saveAndFlush` 후에 `post` 하나

```java
JournalEntry reversal = JournalEntry.reversalOf(...);   // DRAFT 상태
entries.saveAndFlush(reversal);                          // ① DRAFT로 전표+라인 INSERT
reversal.post(userId, Instant.now());                    // ② 그다음 POSTED로 UPDATE
```

DB 트리거 `forbid_posted_line_change`는 **전기된 전표에 라인을 추가하는 것**을 막는다.
처음부터 POSTED로 저장하면 JPA가 "POSTED 전표 INSERT → 라인 INSERT" 순서로 실행해 트리거에 걸린다.
그래서 임시로 저장(flush)한 뒤 전기한다 — **DB 방어선이 앱 코드의 순서까지 강제**하는 예.

### 4-4. `postAll()` — 전부 아니면 전무
한 트랜잭션 안에서 하나씩 전기하다 하나라도 예외가 나면 전부 롤백. 일괄 전기 중 일부만 반영되는 어중간한 상태가 없다.

## 5. 채번과 DB 방어선

### 5-1. `EntryNumberGenerator` (ADR-0011)

```sql
insert into entry_sequence (company_id, entry_date, last_no) values (:c, :d, 1)
on conflict (company_id, entry_date) do update set last_no = entry_sequence.last_no + 1
returning last_no
```

- "없으면 1, 있으면 +1"을 **한 문장**으로 → 그 행이 트랜잭션 끝까지 잠겨 동시 입력이 줄을 선다
- `@Transactional(propagation = MANDATORY)`: 전표 저장과 같은 트랜잭션에서만 호출 가능 → 저장 실패 시 번호도 롤백

### 5-2. V2 마이그레이션의 방어선

| 방어선 | 무엇을 | 언제 검사 |
| --- | --- | --- |
| `CHECK (... (debit = 0) <> (credit = 0))` | 한 줄에 차변·대변 둘 다 금액 | 즉시 |
| `trg_journal_*_balanced` (constraint trigger) | 차대 불일치, 2줄 미만 | **커밋 시점** (`DEFERRABLE INITIALLY DEFERRED`) |
| `trg_journal_entry_immutable`, `trg_journal_line_immutable` | 전기된 전표·라인 수정·삭제·라인 추가 | 즉시 |
| `trg_journal_entry_open_period` | 마감된 기간의 날짜 | 즉시 |
| `ux_journal_entry_reversal_of` (부분 유니크 인덱스) | 같은 전표 두 번 역분개 | 즉시 |
| `uq_journal_line_no` (지연 유니크) | 같은 전표에 같은 줄번호 | 커밋 시점 |

**왜 차대 검사는 커밋 시점에?** 라인을 한 줄씩 INSERT하는 동안은 당연히 차대가 안 맞는다. 즉시 검사하면 첫 줄에서 실패한다.
**왜 줄번호 유니크도 커밋 시점에?** 임시 전표의 라인을 통째로 바꿀 때 JPA(Hibernate)는 **새 라인 INSERT를 옛 라인 DELETE보다 먼저** 실행한다. 즉시 검사하면 "1번 줄 중복"으로 실패한다.

### 5-3. V3 보강 — 방어선에서 찾은 틈 3개

엔진을 다 만든 뒤 "앱을 거치지 않고 SQL로 무엇을 할 수 있나"를 다시 따져 보니 틈이 3개 있었다. 셋 다 실제로 뚫리는 것을 테스트로 확인한 뒤 `V3__journal_guard_hardening.sql`로 막았다.

| 틈 | 왜 뚫렸나 | 막은 방법 |
| --- | --- | --- |
| 임시 전표의 라인 2줄을 `entry_id`만 바꿔 **전기된 전표로 옮기기** | 불변 트리거는 옮기기 **전** 전표(임시)만 보고, 차대 트리거는 옮긴 **뒤** 전표만 봤다. 차대가 맞는 2줄을 옮기면 둘 다 통과 | 라인은 태어난 전표를 떠날 수 없다 (`entry_id` 변경 금지) |
| 마감된 달에 남은 **임시 전표를 전기** | 마감 트리거가 `INSERT`와 날짜 변경만 감시. 전기는 `status`만 바꾼다 | 트리거가 `status` 변경도 감시 + 기간 행에 공유 잠금 |
| 역분개 전표가 **원전표보다 앞 날짜**, 또는 임시 전표·다른 회사 전표를 가리킴 | 역분개 대상을 DB가 전혀 확인하지 않았다 (앱도 날짜는 확인 안 함) | `trg_journal_entry_reversal_target` + 서비스의 `REVERSAL_DATE_INVALID` |

**공유 잠금(`FOR SHARE`)은 왜 필요한가?** 트리거가 "기간이 열려 있나"를 확인한 **직후**, 전표가 커밋되기 **직전**에 다른 사람이 그 달을 마감하면 검사는 통과했는데 결과는 마감된 달의 전표가 된다. 검사와 커밋 사이의 틈(time-of-check to time-of-use)이다.

```sql
select p.status into v_status from fiscal_period p join fiscal_year y on … for share of p;
```

- 전표를 쓰는 트랜잭션들은 기간 행에 **공유** 잠금을 건다 → 서로는 막지 않는다 (채번처럼 줄을 서지 않는다)
- 마감은 기간 행을 `UPDATE` 해야 하므로 **진행 중인 전표 트랜잭션이 끝날 때까지 기다린다**
- 마감이 먼저 커밋되면, 기다리던 전표 트랜잭션은 바뀐 행(`CLOSED`)을 읽고 거부된다

테스트 `periodCannotBeClosedWhileAnEntryIsBeingWritten`은 전표를 넣고 커밋하지 않은 채로 다른 커넥션에서 마감을 시도해, 0.3초 안에 잠금을 못 얻고 포기하는 것(SQLSTATE `55P03`)을 확인한다.

**한 가지 더 — 라인만 고치면 version이 안 올랐다** (2026-11-11 검토에서 발견). `JournalEntry.lines`는 `mappedBy` 쪽이라 라인 목록만 바뀌면 JPA가 전표의 `@Version`을 올리지 않는다. 그러면 같은 임시 전표를 두 사람이 열어 두고 각자 금액만 고쳐 저장할 때 나중 저장이 앞 저장을 조용히 덮어쓴다. `JournalService.update`가 저장 후 version이 그대로면 `PESSIMISTIC_FORCE_INCREMENT`로 강제로 올린다 (테스트 `editingOnlyLinesBumpsVersion`).

**남겨 둔 한계**: 역분개 전표의 라인이 원전표의 정확한 반대인지는 DB가 검사하지 않는다 (`JournalEntry.reversalOf`의 책임). 마감 시 그 달에 임시 전표가 남아 있으면 어떻게 할지는 5단계(마감 기능)에서 정한다.

## 6. 규칙 ↔ 코드 ↔ 테스트

| 회계 규칙 | 코드 | 테스트 |
| --- | --- | --- |
| 차변 합계 = 대변 합계 | `JournalEntry.validate` + 트리거 | `JournalEntryTest.unbalancedIsRejected`, `JournalApiTest > 작성 > 차대 불일치`, `JournalDatabaseGuardTest.unbalancedEntryIsRejectedAtCommit` |
| 2줄 이상 | 같은 곳 | `tooFewLines`, `singleLineEntryIsRejected` |
| 한쪽 금액만, 양의 정수 | `validate` + CHECK | `invalidAmounts`, `lineWithBothSidesIsRejected` |
| 거래처 필수 계정 | `JournalService.resolveLines` | `JournalApiTest > 작성 > 거래처 필수` |
| 시스템 계정 금지 | 같은 곳 | `> 작성 > 결산 전용 시스템 계정` |
| 열린 기간에만 (생성·전기 모두) | `FiscalCalendar` + 트리거(공유 잠금) | `> 전기 > 마감된 달`, `closedPeriodIsGuarded`, `draftInClosedPeriodCannotBePosted`, `periodCannotBeClosedWhileAnEntryIsBeingWritten` |
| 전기 후 불변 | `requireDraft` + 트리거 2개 | `postedEntryIsImmutable`(단위·API), `postedEntryCannotBeTamperedWith`, `linesCannotBeMovedIntoPostedEntry`(SQL) |
| 정정은 역분개, 한 번만, 원전표일 이후 | `reversalDrafts`, `reverse` + 부분 유니크 + 대상 트리거 | `> 역분개` 5개, `reversalTargetIsGuarded` |
| 일자별 번호, 동시 입력 안전 | `EntryNumberGenerator` | `createsDraftWithDailyNumber`, `EntryNumberConcurrencyTest` |
| 일괄 전기는 전부 아니면 전무 | `postAll` (한 트랜잭션) | `> 전기 > 일괄 전기는 …` |
| 라인은 전표를 통해서만 생성 | `JournalLine` 생성자 package-private | `ArchitectureTest.linesAreCreatedOnlyThroughEntry` |

## 7. 면접에서 받을 만한 질문

**Q. 차대 검증을 앱과 DB 두 군데서 하는 건 중복 아닌가요?**
역할이 다릅니다. 앱은 사용자에게 "차액 100만 원"처럼 친절한 오류를 즉시 주고, DB는 배치·데이터 이관·직접 SQL처럼 앱을 거치지 않는 경로까지 막는 마지막 방어선입니다. 회계 데이터는 한 번 깨지면 원인을 찾기 매우 어려워서 비용 대비 가치가 큽니다 (ADR-0006).

**Q. 전기된 전표를 왜 수정하지 못하게 했나요?**
마감 이후에도 과거 장부가 조용히 바뀌면 신고 자료와 장부가 어긋나고 감사 추적이 불가능합니다. 정정은 역분개로 하면 "언제 무엇을 왜 고쳤는지"가 전표로 남습니다 (ADR-0003).

**Q. 전표번호를 `max + 1`로 안 하고 별도 테이블을 둔 이유는?**
`max + 1`은 동시에 두 트랜잭션이 같은 max를 읽어 번호가 겹칩니다. `INSERT … ON CONFLICT … RETURNING` 한 문장이면 행 잠금으로 직렬화되고, 저장이 롤백되면 번호도 롤백됩니다. 시퀀스는 롤백돼도 안 돌아가서 번호가 빕니다 (ADR-0011).

**Q. 도메인 규칙과 서비스 규칙을 어떻게 나눴나요?**
전표 한 장만 보고 판단 가능한 규칙은 `JournalEntry`에 두어 어떤 경로로 만들든 항상 적용되고 DB 없이 단위 테스트됩니다. 계정 상태나 기간 마감처럼 다른 데이터가 필요한 규칙은 서비스에 둡니다.

**Q. 금액을 BigDecimal 대신 long으로 한 이유는?**
원화만 다루고 원 미만 단위가 없어서 정수면 충분하고, 연산이 빠르고 실수 여지가 적습니다. 오버플로는 `Math.addExact`로 감지합니다. 다중 통화가 필요해지면 재설계 대상입니다 (ADR-0002).

**Q. 역분개를 만들 때 왜 바로 POSTED로 저장하지 않았나요?**
DB 트리거가 전기된 전표에 라인 추가를 막기 때문에, 임시로 저장한 뒤 전기합니다. DB 규칙이 앱의 저장 순서까지 강제한 사례입니다.

## 8. 스스로 해 보기

```bash
cd backend
./gradlew test --tests "com.mzerp.journal.*"      # 전표 테스트만
```

- [ ] `JournalEntry.validate`의 `if (!debit.equals(credit))` 블록을 주석 처리하고 테스트를 돌려 보세요. 어떤 테스트가 깨지나요? **DB 방어선 테스트는 왜 여전히 통과할까요?**
- [ ] `EntryNumberGenerator`의 SQL을 `select coalesce(max(entry_no), 0) + 1 from journal_entry where …`로 바꾸면 `EntryNumberConcurrencyTest`는 어떻게 될까요?
- [ ] `JournalService.reverse`에서 `entries.saveAndFlush(reversal);` 줄을 지우면 어떤 오류가 날까요? 오류 메시지에서 어떤 트리거인지 찾아보세요
- [ ] 로컬 실행(`./gradlew bootTestRun`) 후 http://localhost:8080/swagger-ui.html 에서 시나리오 #6(받을어음 만기)을 직접 입력 → 전기 → 역분개 해 보세요
- [ ] 시나리오 #16(급여)을 전표로 입력하려면 라인이 몇 줄 필요하고, 각 줄의 차대는?
