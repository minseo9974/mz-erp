# ADR-0011: 전표번호는 회사·일자별 연번을 채번 테이블 한 줄 잠금으로 발급한다

- 상태: 채택 (2026-11-10)

## 맥락
전산회계 프로그램처럼 전표번호는 **날짜마다 1번부터** 매긴다 (`2026-03-10`의 1번, 2번 …).
여러 사람이 같은 날짜로 동시에 전표를 입력해도 **같은 번호가 두 번 나오면 안 된다.**

흔한 실수: `select max(entry_no) + 1 from journal_entry where …` — 두 트랜잭션이 동시에 같은 max를 읽어 번호가 겹친다.

## 결정
`entry_sequence(company_id, entry_date, last_no)` 테이블을 두고 한 문장으로 채번한다.

```sql
insert into entry_sequence (company_id, entry_date, last_no) values (:c, :d, 1)
on conflict (company_id, entry_date) do update set last_no = entry_sequence.last_no + 1
returning last_no
```

- 같은 회사·날짜의 행은 이 문장을 실행한 트랜잭션이 끝날 때까지 **행 잠금**이 걸려, 다른 트랜잭션은 기다렸다가 다음 번호를 받는다
- 채번은 전표 저장과 **같은 트랜잭션**(`Propagation.MANDATORY`)에서만 → 저장이 실패해 롤백되면 증가분도 롤백되어 번호가 비지 않는다
- PostgreSQL 시퀀스(`nextval`)는 롤백돼도 되돌아가지 않아 번호가 비므로 쓰지 않았다

## 결과
- 좋은 점: 동시 입력 20건에서 1~20번이 겹치거나 비지 않음을 테스트로 확인 (`EntryNumberConcurrencyTest`)
- 감수할 점
  - 같은 회사·같은 날짜의 전표 저장은 **직렬화**된다 (잠금 대기). 1인~소규모 회사 규모에선 문제없음
  - 임시 전표를 삭제하면 그 번호는 빈다. 전기된 전표는 지울 수 없으므로 장부에 남는 전기 전표 번호에는 영향이 없다
- 면접 포인트: "max+1이 왜 위험한가", "시퀀스를 안 쓴 이유", "번호 구멍과 동시성의 트레이드오프"
