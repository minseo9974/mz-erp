-- 2단계: 기초잔액(OPENING) 전표에는 재무상태표 계정만 들어갈 수 있다 (ADR-0004, 회계 노트 03)
-- 수익·비용은 한 해 동안의 흐름이라 해가 바뀌면 0에서 시작한다. 전기의 순이익은 이월이익잉여금에 이미 들어 있다.
-- 앱(OpeningBalanceService)이 먼저 검사하고, 이 트리거는 직접 SQL 같은 다른 경로를 막는다.

create function forbid_income_account_in_opening() returns trigger
    language plpgsql as
$$
declare
    v_code varchar;
begin
    select a.code
      into v_code
      from journal_entry e
      join account a on a.id = new.account_id
     where e.id = new.entry_id
       and e.entry_type = 'OPENING'
       and a.statement <> 'BS';

    if found then
        raise exception '기초잔액 전표 %에는 재무상태표 계정만 넣을 수 있습니다 (계정 %)', new.entry_id, v_code
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;

create trigger trg_journal_line_opening_bs
    before insert or update of account_id on journal_line
    for each row execute function forbid_income_account_in_opening();
