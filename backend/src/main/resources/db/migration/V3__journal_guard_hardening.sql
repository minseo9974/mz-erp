-- 2단계 보강: V2 방어선에서 찾은 틈 3개를 막는다.
-- 셋 다 앱(JournalService)으로는 못 하는 일이지만, "DB가 마지막 방어선"이려면 직접 SQL로도 안 돼야 한다.
-- 해설: docs/walkthrough/02-journal-engine.md §5-3

-- ---------------------------------------------------------------------------
-- 틈 1: 임시 전표의 라인을 entry_id만 바꿔 전기된 전표로 옮길 수 있었다
--   불변 트리거는 "옮기기 전 전표"(임시)만, 차대 트리거는 "옮긴 뒤 전표"(차대 맞는 2줄이 늘어남)만 봤다.
--   → 라인은 태어난 전표를 떠날 수 없게 한다. 그러면 두 트리거가 보는 전표가 항상 같다.
-- ---------------------------------------------------------------------------
create or replace function forbid_posted_line_change() returns trigger
    language plpgsql as
$$
declare
    v_entry_id bigint := case when tg_op = 'INSERT' then new.entry_id else old.entry_id end;
begin
    if tg_op = 'UPDATE' and new.entry_id <> old.entry_id then
        raise exception '전표 라인은 다른 전표로 옮길 수 없습니다 (전표 % → %)', old.entry_id, new.entry_id
            using errcode = 'restrict_violation';
    end if;
    if exists (select 1 from journal_entry where id = v_entry_id and status = 'POSTED') then
        raise exception '전기된 전표 %의 라인은 추가·수정·삭제할 수 없습니다', v_entry_id
            using errcode = 'restrict_violation';
    end if;
    return case when tg_op = 'DELETE' then old else new end;
end;
$$;

-- ---------------------------------------------------------------------------
-- 틈 2: 마감 검사가 INSERT와 날짜 변경만 봐서, 마감된 달에 남은 임시 전표를 전기할 수 있었다
--   → 상태 변경(전기)도 검사한다.
--   그리고 "검사한 직후에 마감되는" 틈도 막는다: 기간 행에 공유 잠금(FOR SHARE)을 건다.
--     · 전표를 쓰는 트랜잭션끼리는 서로 막지 않는다 (공유 잠금끼리는 공존)
--     · 마감(기간 행 UPDATE)은 진행 중인 전표 트랜잭션이 끝날 때까지 기다린다
--     · 마감이 먼저 커밋되면, 기다리던 전표 트랜잭션은 CLOSED를 보고 거부된다
-- ---------------------------------------------------------------------------
create or replace function forbid_closed_period_entry() returns trigger
    language plpgsql as
$$
declare
    v_status varchar;
begin
    select p.status
      into v_status
      from fiscal_period p
      join fiscal_year y on y.id = p.fiscal_year_id
     where y.company_id = new.company_id
       and new.entry_date between p.start_date and p.end_date
       for share of p;

    if v_status = 'CLOSED' then
        raise exception '마감된 기간(%)에는 전표를 만들거나 전기할 수 없습니다', new.entry_date
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;

drop trigger trg_journal_entry_open_period on journal_entry;

create trigger trg_journal_entry_open_period
    before insert or update of entry_date, status on journal_entry
    for each row execute function forbid_closed_period_entry();

-- ---------------------------------------------------------------------------
-- 틈 3: 역분개 전표가 가리키는 원전표를 DB가 전혀 확인하지 않았다
--   → 같은 회사의, 전기된, 역분개가 아닌 전표만 가리킬 수 있고, 역분개일은 원전표일보다 빠를 수 없다.
--   (취소가 원거래보다 먼저 장부에 나오면 그 사이 기간의 잔액이 실제와 달라진다)
--   라인이 원전표의 정확한 반대인지까지는 DB에서 검사하지 않는다 — 그건 JournalEntry.reversalOf의 책임.
-- ---------------------------------------------------------------------------
create function check_reversal_target() returns trigger
    language plpgsql as
$$
declare
    v_original journal_entry%rowtype;
begin
    select * into v_original from journal_entry where id = new.reversal_of_id;

    if v_original.company_id <> new.company_id then
        raise exception '역분개 전표는 같은 회사의 전표만 가리킬 수 있습니다 (원전표 %)', new.reversal_of_id
            using errcode = 'restrict_violation';
    end if;
    if v_original.status <> 'POSTED' or v_original.entry_type = 'REVERSAL' then
        raise exception '역분개 대상은 전기된 일반 전표여야 합니다 (원전표 %: %, %)',
            new.reversal_of_id, v_original.status, v_original.entry_type
            using errcode = 'restrict_violation';
    end if;
    if new.entry_date < v_original.entry_date then
        raise exception '역분개일(%)은 원전표일(%)보다 빠를 수 없습니다', new.entry_date, v_original.entry_date
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;

create trigger trg_journal_entry_reversal_target
    before insert or update of reversal_of_id, entry_date on journal_entry
    for each row
    when (new.reversal_of_id is not null)
    execute function check_reversal_target();
