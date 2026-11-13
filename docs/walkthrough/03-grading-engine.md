# 같이 읽기 03 — 채점 엔진 (미션 · 결재 요청 · 반려 사유)

실습자가 ERP에서 전표를 쓰고 "결재 요청"을 누르면 무슨 일이 일어나는지 따라갑니다.
설계: [design/training-site.md](../design/training-site.md) §7, [ADR-0012](../adr/0012-learning-layer-on-top-of-erp.md) · [0013](../adr/0013-state-based-grading.md) · [0014](../adr/0014-sandbox-company-per-learner.md) · [0015](../adr/0015-course-content-as-files.md)

## 1. 한 장으로 보기

```
과정 시작          SandboxFactory        실습 회사 생성 (계정표·거래처·회계연도·기초잔액 전기)
   │                                    실습자 = 작성자(WRITER), 팀장 = 시스템 계정(승인자)
미션 시작          LearningService.start  지금 장부 잔액을 저장 (baseline)
   │
전표 작성          (ERP 일반전표 API)      실습 층은 관여하지 않는다 — 실제 ERP를 쓴다
   │
결재 요청          LearningService.submit
   │   ① 전기된 잔액 + 임시 전표 = "결재가 나면 이렇게 된다"
   │   ② 그것 − baseline = 실제 변화
   │   ③ Grader: 실제 변화 = 기대 변화?
   ├─ 같다 → 팀장이 임시 전표를 전기, 미션 PASSED
   └─ 다르다 → 반려 사유, 전표는 임시로 남는다 (고쳐서 다시 요청)
```

## 2. 읽는 순서

| # | 파일 | 볼 것 |
| --- | --- | --- |
| 1 | `learning/grading/Balances.java` | 변화를 어떻게 표현하나: (계정, 거래처) → 차변 − 대변 |
| 2 | `learning/grading/Grader.java` | 통과 판정과 오답 유형 분류. DB·스프링 없음 |
| 3 | `GraderTest.java` | 오답 유형마다 한 테스트. **여기부터 읽어도 좋다** |
| 4 | `ledger/BalanceMapper.java` | 채점의 재료: 전기된 전표 라인의 계정·거래처별 합계 (MyBatis) |
| 5 | `learning/course/CourseCatalog.java`, `data/course/accounting-basics.toml` | 미션은 시나리오 거래를 가리킬 뿐, 정답을 따로 적지 않는다 |
| 6 | `learning/SandboxFactory.java` | 실습 회사와 팀장 계정 |
| 7 | `learning/LearningService.java` | `start` → `submit` → `reveal` |
| 8 | `LearningApiTest.java` | 반려 세 번 뒤 승인되는 한 바퀴 |
| 9 | `resources/db/migration/V5__learning.sql` | 실습 층 테이블 3개. ERP 테이블은 그대로 |

## 3. 변화로 채점한다 — `Balances`, `Grader`

시나리오 #5 "한빛전자 외상매출금 12,000,000 회수"의 정답 분개는

```
(차) 103 보통예금 12,000,000 / (대) 108 외상매출금 12,000,000 (한빛전자)
```

이 분개가 장부에 만드는 **변화**는 다음 두 줄입니다. 차변이면 +, 대변이면 −.

```
(103, 거래처 없음) : +12,000,000
(108, 00101)      : −12,000,000
```

채점은 실습자의 전표 줄을 정답 줄과 맞대 보지 않고, 실습자가 만든 변화가 이 두 줄과 같은지만 봅니다.

- 줄 순서가 달라도, 전표를 두 장으로 나눠도 변화는 같다 → 통과 (`sameChangePasses`)
- 거꾸로 넣었다가 역분개하고 다시 넣어도, 세 전표를 합친 변화는 같다 → 통과 (`correctedByReversalPasses`). 실무에서 올바른 정정 절차이기 때문
- 장부는 전표 라인에서만 나온다는 불변식 5가 채점의 근거다

**틀렸을 때**는 차이의 모양으로 이유를 가립니다 (`Grader.grade`, 위에서부터 차례로 검사).

| 유형 | 판별 | 테스트 |
| --- | --- | --- |
| `NO_ENTRY` | 실제 변화가 비어 있다 | `nothingEntered` |
| `SIDE_SWAPPED` | 실제 변화의 부호를 전부 뒤집으면 기대와 같다 | `sidesSwapped` |
| `WRONG_PARTNER` | 거래처를 무시하고 계정별로 합치면 같다 | `wrongPartner` |
| (통과) | 정답이 거래처를 따지지 않는 계정(보통예금)에 거래처를 붙였다 — 틀린 처리가 아니므로 떼고 본다 (`ignoreExtraPartners`) | `extraPartnerOnAccountWithoutPartnerPasses` |
| `WRONG_ACCOUNT` | 계정별 차이가 딱 두 개: 있어야 할 계정 하나가 없고, 없어야 할 계정 하나가 같은 금액으로 있다 | `wrongAccount` |
| `WRONG_AMOUNT` | 바뀐 계정의 집합은 같고 금액만 다르다 | `wrongAmount` |
| `UNEXPECTED_CHANGE` | 그 밖 | `unexpectedChange` |
| `WRONG_DATE` | 변화는 맞는데 전표 일자가 거래일이 아니다 (서비스가 붙임) | `LearningApiTest` |

`WRONG_ACCOUNT`의 문장은 두 계정이 얼마나 가까운지에 따라 달라집니다.

- 같은 중분류 (보통예금 ↔ 현금): "당좌자산인 것은 맞으니 그 안에서 다시 찾아보세요"
- 같은 대분류 (보통예금 ↔ 기계장치): "자산인 것은 맞지만 유형자산이 아니에요"
- 다른 대분류 (외상매출금 ↔ 제품매출): "수익 계정인데, 이 거래에서 바뀌는 것은 자산이에요" — 회수할 때 매출을 한 번 더 잡는 흔한 실수

반려 사유는 **정답 계정을 말하지 않습니다** (`findingsDoNotRevealTheAnswer`). 계정 코드로 분기하지 않고 계정의 속성(대분류·중분류)만 씁니다 (불변식 6).

## 4. 결재 요청 — `LearningService.submit`

```java
Map<Balances.Key, Long> pending = postedBalances(companyId);          // 전기된 잔액
for (EntryView draft : drafts) { …Balances.add(pending, …) }           // + 임시 전표
Map<Balances.Key, Long> actual = Balances.minus(pending, baseline);    // − 미션 시작 때 잔액
List<Finding> findings = grade(mission, actual, companyId);
```

- **왜 임시 전표를 더하나?** 실습자는 작성자라서 직접 전기하지 못한다. 그래서 "전기하면 이렇게 된다"를 먼저 계산해 채점하고, 맞을 때만 팀장이 전기한다. 틀린 전표가 장부에 들어가지 않는다
- **baseline은 어디에?** `mission_attempt.baseline`에 JSON으로. ERP 테이블에 실습용 컬럼을 넣지 않기 위해서다 (ADR-0012)
- **전기는 누가?** `journal.post(companyId, draftId, teamLead)` — 화면에서 승인자가 누르는 것과 같은 서비스. 그래서 마감 검사, DB 트리거를 그대로 거친다
- **미션을 시작할 때 세 가지를 확인한다** (`start`). 채점이 "시작 이후의 변화"라서, 그 변화에 다른 것이 섞이면 안 되기 때문이다
  - 한 번에 하나만 (`V5`의 부분 유니크 인덱스 + 서비스 검사) — 두 미션의 변화가 섞이면 어느 미션의 것인지 가를 수 없다 (`MISSION_IN_PROGRESS`)
  - 과정 순서대로 — 뒤 미션은 앞 미션이 장부에 남긴 결과 위에서 푼다 (`MISSION_LOCKED`, 테스트 `missionsUnlockInOrder`)
  - 남은 임시 전표가 없을 것 — 미션 밖에서 써 둔 전표가 이번 결재에 함께 올라가 전기되면 안 된다 (`MISSION_DRAFTS_LEFT`, `leftoverDraftsBlockStart`)
- 제출할 때마다 `attempt_submission`에 통과 여부와 사유를 남긴다. 어떤 미션에서 어떤 오답이 많은지 볼 재료

## 5. 실습 회사와 팀장 — `SandboxFactory`

- 사용자마다 회사를 하나 만든다. 격리는 기존의 `company_id`와 회사별 권한이 그대로 해 준다 (`othersCannotSeeMyEnrollment`)
- 회사의 관리자는 팀장 시스템 계정, 실습자는 작성자. 실습자가 `/post`를 직접 부르면 403 (`learnerCannotPostDirectly`) — 신입의 실제 권한과 같다
- 기초잔액까지는 `ScenarioReplayer.prepare`가 ERP 서비스로 넣는다. 테이블을 직접 채우지 않는다

## 6. 콘텐츠 — `data/course/*.toml`

```toml
[[chapters.missions]]
key = "receivable-collection"
scenario = 5              # 날짜와 정답 분개는 시나리오 거래 #5에서 가져온다
title = "외상매출금 회수"
brief = "…"
hints = ["…", "…"]
explanation = '''…'''   # 끝낸 뒤에만 보여 주는 해설: 왜 이 분개인가, 흔한 실수, 실무에서는
```

증빙(`[[chapters.missions.evidence]]`: 통장 거래내역 `bank`, 확인증 `slip`, 약속어음 `note`)도 같은 파일에 있습니다. 증빙은 손으로 적는 숫자라 정답과 어긋날 수 있으므로 `CourseContentTest`가 맞춰 봅니다: 통장의 입출금과 잔액은 정답 분개를 차례로 전기했을 때의 보통예금 장부와 같아야 하고(`bankStatementsAgreeWithTheLedger`), 확인증·어음의 금액은 거래 금액과 같아야 합니다. 실습자가 전표를 올린 뒤 통장 잔액과 장부 잔액을 비교해 볼 수 있는 것도 이 덕분입니다.

정답을 미션에 다시 적지 않으므로 시나리오와 미션이 어긋날 수 없습니다. 시나리오 전체가 ERP에서 정답 재무제표를 내는지는 골든 테스트가, 미션이 갖출 것을 갖췄는지는 `CourseContentTest`가 확인합니다.

## 6-1. 끝낸 미션 다시 보기 · 다시 풀기 — `LearningService.review`, `retry`

끝낸 미션을 누르면 정답 분개와 해설을 다시 볼 수 있고, 다시 풀 수도 있습니다. 여기서 정해야 했던 것은 **"다시 푼 전표를 어떻게 할 것인가"** 입니다.

| 방법 | 문제 |
| --- | --- |
| 다시 푼 전표도 전기한다 | 그 거래는 이미 장부에 있다. 같은 거래가 두 번 기록되어 뒤 미션의 잔액이 모두 틀어진다 |
| 먼저 전기된 전표를 역분개하고 다시 전기한다 | 장부는 맞지만 다시 풀 때마다 역분개 전표가 쌓이고, 그 미션이 어느 전표를 전기했는지 따로 기록해야 한다 |
| **채점만 하고 전표는 지운다 (선택)** | 장부가 연습 전과 같다. 전표를 쓰는 화면과 채점은 본 시도와 똑같다 |

그래서 다시 풀기는 `mission_attempt.practice = true`인 **연습 시도**로 따로 남습니다 (V6).

- 채점은 본 시도와 같은 코드입니다. 연습을 시작할 때의 잔액을 저장하고, 임시 전표가 만들 변화를 정답과 비교합니다. 그 거래가 이미 장부에 있어도 "시작 이후의 변화"만 보므로 상관없습니다 — 변화로 채점하는 방식(ADR-0013)이 여기서도 그대로 통합니다
- 다른 점은 통과한 뒤 한 줄입니다: 본 시도는 `journal.post`, 연습은 `journal.delete` (`retryGradesWithoutTouchingTheLedger`)
- "정답 보고 넘어가기"는 연습에서 "그만두기"입니다. 임시 전표만 지우고 정답 전표를 또 넣지 않습니다 (`quittingPracticeLeavesTheLedgerAlone`)
- 본 시도의 결과(통과·정답 확인)와 기록은 연습으로 바뀌지 않습니다. 미션마다 본 시도는 하나라는 것을 부분 유니크 인덱스(`where not practice`)가 지킵니다
- 연습도 "한 번에 하나만" 규칙을 따릅니다. 연습 중에 다음 미션을 시작하면 두 변화가 섞이기 때문입니다

다시 보기(`review`)는 정답이 들어 있으므로 본 시도를 끝낸 뒤에만 열립니다. 끝내기 전에는 409 `MISSION_NOT_FINISHED`이고, 진행 중에 내려가는 응답에는 해설이 없습니다 (`reviewShowsAnswerAndExplanationOnlyAfterFinishing`).

## 7. 면접에서 받을 만한 질문

**Q. 정답이 여러 개일 수 있는 문제를 어떻게 채점했나요?**
입력을 비교하지 않고 장부 잔액의 변화를 비교했습니다. 같은 거래를 맞게 처리하는 여러 경로가 모두 같은 변화를 만들기 때문입니다. 계정 선택 자체가 여럿 인정되는 경우는 미션에 대안 분개(`accept`)를 적습니다.

**Q. 실습 기능을 넣으면서 ERP 코드를 고쳤나요?**
채점을 위해서는 고치지 않았습니다. 실습 층은 화면과 같은 공개 서비스만 쓰고, ERP가 실습 층을 참조하면 ArchUnit 테스트가 실패합니다. 시작 시점 잔액처럼 채점에만 필요한 데이터는 실습 층 테이블에 둡니다.

**Q. 채점기를 어떻게 테스트했나요?**
채점기는 "기대 변화"와 "실제 변화" 두 묶음만 받는 순수 함수라 DB 없이 오답 유형마다 단위 테스트를 뒀습니다. DB·권한·전기까지 이어지는 흐름은 통합 테스트 한 벌로 확인합니다.

**Q. 이 방식의 한계는?**
서로 상쇄되는 두 가지 실수(같은 계정·거래처에서 +와 −가 맞아떨어지는 경우)는 잡지 못합니다. 미션 도중 미션과 무관한 전표를 넣으면 변화가 섞여 반려됩니다. 둘 다 알고 받아들였고 ADR-0013에 적었습니다.

## 8. 스스로 해 보기

```bash
cd backend
./gradlew test --tests "com.mzerp.learning.*"
```

- [ ] `GraderTest`에 "금액은 맞는데 보통예금과 외상매출금을 **둘 다** 다른 계정으로 쓴 경우"를 추가해 보세요. 어떤 유형이 나올까요? 왜 `WRONG_ACCOUNT`가 아닐까요?
- [ ] `Grader.grade`에서 `SIDE_SWAPPED` 검사를 `WRONG_PARTNER` 검사 뒤로 옮기면 깨지는 테스트가 있을까요? 검사 순서는 왜 중요할까요?
- [ ] `LearningService.submit`에서 임시 전표를 더하는 `for` 문을 지우면 `LearningApiTest`의 어느 단계에서 무엇이 나올까요?
- [ ] 시나리오 #7(외상매입금 지급)을 251 외상매입금이 아니라 253 미지급금으로 쓰면 어떤 문장이 나올지 먼저 예상하고, 계정표의 중분류를 확인해 보세요
