# ADR-0006: 차대 일치는 앱과 DB 양쪽에서 검증한다

- 상태: 채택 (2026-11-09)

## 맥락
차대 불일치 전표가 한 장이라도 들어가면 시산표가 깨지고, 원인을 찾기 매우 어렵다.
앱 검증만 두면 배치·마이그레이션·직접 SQL 같은 다른 경로로 깨진 데이터가 들어올 수 있다.

## 결정
1. **앱**: `JournalEntry` 도메인 객체가 생성·변경될 때 차대 합계를 검증 (즉시 사용자에게 오류 표시)
2. **DB**: PostgreSQL 지연 제약 트리거로 커밋 시점에 전표별 합계를 다시 확인

```sql
CREATE CONSTRAINT TRIGGER trg_journal_entry_balanced
AFTER INSERT OR UPDATE OR DELETE ON journal_line
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION check_journal_entry_balanced();
-- 함수: 영향받은 entry_id의 SUM(debit) <> SUM(credit)이면 RAISE EXCEPTION
```

라인을 한 줄씩 넣는 동안에는 당연히 불일치 상태이므로, 즉시 검사가 아니라 **커밋 시점(DEFERRED)** 에 검사한다.

## 결과
- 좋은 점: 어떤 경로로 들어와도 깨진 전표는 커밋되지 않음
- 감수할 점: 트리거 비용 → 대량 업로드 성능 측정 필요 (7단계). 트리거 로직도 Testcontainers 테스트 대상
