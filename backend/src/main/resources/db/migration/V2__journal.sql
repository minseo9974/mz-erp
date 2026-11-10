-- 2단계: 전표(journal_entry)·전표 라인(journal_line)·전표번호 채번(entry_sequence)
-- 설계: docs/design/erd.md, docs/accounting-notes/02-journal-posting-reversal.md
-- 앱(JournalEntry/JournalService)이 먼저 검증하고, 아래 제약·트리거는 다른 경로(배치·직접 SQL)를 막는 마지막 방어선이다.

-- ---------------------------------------------------------------------------
-- 전표번호: 회사·일자별 연번. 한 줄을 잠그고 +1 해서 동시 입력에도 중복이 없다 (ADR-0011)
-- ---------------------------------------------------------------------------
create table entry_sequence (
    company_id bigint not null references company (id),
    entry_date date   not null,
    last_no    int    not null check (last_no > 0),
    primary key (company_id, entry_date)
);

-- ---------------------------------------------------------------------------
-- 전표 머리
-- ---------------------------------------------------------------------------
create table journal_entry (
    id              bigserial primary key,
    company_id      bigint       not null references company (id),
    fiscal_year_id  bigint       not null references fiscal_year (id),
    entry_date      date         not null,
    entry_no        int          not null check (entry_no > 0),
    entry_type      varchar(20)  not null check (entry_type in ('GENERAL', 'SALES_PURCHASE', 'OPENING', 'CLOSING', 'REVERSAL')),
    status          varchar(10)  not null check (status in ('DRAFT', 'POSTED')),
    description     varchar(200),
    reversal_of_id  bigint references journal_entry (id),
    reversal_reason varchar(200),
    created_by      bigint       not null references app_user (id),
    created_at      timestamptz  not null default now(),
    posted_by       bigint references app_user (id),
    posted_at       timestamptz,
    version         int          not null default 0,
    unique (company_id, entry_date, entry_no),
    -- 전기된 전표에만 전기 시각·전기자가 있다
    check ((status = 'POSTED') = (posted_at is not null and posted_by is not null)),
    -- 역분개 전표만 원전표를 가리킨다
    check ((entry_type = 'REVERSAL') = (reversal_of_id is not null))
);

-- 한 전표는 한 번만 역분개할 수 있다
create unique index ux_journal_entry_reversal_of on journal_entry (reversal_of_id) where reversal_of_id is not null;
create index ix_journal_entry_company_date on journal_entry (company_id, entry_date);

-- ---------------------------------------------------------------------------
-- 전표 라인: 한 줄은 차변 또는 대변 한쪽 금액만 가진다 (노트 01)
-- ---------------------------------------------------------------------------
create table journal_line (
    id            bigserial primary key,
    entry_id      bigint        not null references journal_entry (id) on delete cascade,
    line_no       int           not null check (line_no > 0),
    account_id    bigint        not null references account (id),
    partner_id    bigint references partner (id),
    debit_amount  numeric(18, 0) not null default 0,
    credit_amount numeric(18, 0) not null default 0,
    memo          varchar(200),
    check (debit_amount >= 0 and credit_amount >= 0 and ((debit_amount = 0) <> (credit_amount = 0))),
    -- 임시 전표의 라인을 통째로 바꿀 때 JPA는 새 라인 INSERT를 옛 라인 DELETE보다 먼저 실행한다.
    -- 그래서 줄번호 중복 검사는 커밋 시점으로 미룬다.
    constraint uq_journal_line_no unique (entry_id, line_no) deferrable initially deferred
);

create index ix_journal_line_account on journal_line (account_id, entry_id);
create index ix_journal_line_partner on journal_line (partner_id) where partner_id is not null;

-- ---------------------------------------------------------------------------
-- 방어선 1: 차대 일치 + 2줄 이상 — 커밋 시점에 검사 (ADR-0006)
--   라인을 한 줄씩 넣는 동안은 당연히 불일치이므로 DEFERRABLE INITIALLY DEFERRED
-- ---------------------------------------------------------------------------
create function check_journal_entry_balanced() returns trigger
    language plpgsql as
$$
declare
    v_entry_id bigint;
    v_debit    numeric;
    v_credit   numeric;
    v_lines    int;
begin
    if tg_table_name = 'journal_entry' then
        v_entry_id := new.id;
    elsif tg_op = 'DELETE' then
        v_entry_id := old.entry_id;
    else
        v_entry_id := new.entry_id;
    end if;

    -- 전표 자체가 삭제됐으면(임시 전표 삭제) 검사할 것이 없다
    if not exists (select 1 from journal_entry where id = v_entry_id) then
        return null;
    end if;

    select coalesce(sum(debit_amount), 0), coalesce(sum(credit_amount), 0), count(*)
      into v_debit, v_credit, v_lines
      from journal_line
     where entry_id = v_entry_id;

    if v_lines < 2 then
        raise exception '전표 %: 라인이 2줄 미만입니다 (% 줄)', v_entry_id, v_lines
            using errcode = 'check_violation';
    end if;
    if v_debit <> v_credit then
        raise exception '전표 %: 차변 % ≠ 대변 %', v_entry_id, v_debit, v_credit
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

create constraint trigger trg_journal_line_balanced
    after insert or update or delete on journal_line
    deferrable initially deferred
    for each row execute function check_journal_entry_balanced();

create constraint trigger trg_journal_entry_balanced
    after insert on journal_entry
    deferrable initially deferred
    for each row execute function check_journal_entry_balanced();

-- ---------------------------------------------------------------------------
-- 방어선 2: 전기된 전표는 불변 (ADR-0003)
-- ---------------------------------------------------------------------------
create function forbid_posted_entry_change() returns trigger
    language plpgsql as
$$
begin
    if old.status = 'POSTED' then
        raise exception '전기된 전표 %는 수정·삭제할 수 없습니다 (역분개로 정정)', old.id
            using errcode = 'restrict_violation';
    end if;
    return case when tg_op = 'DELETE' then old else new end;
end;
$$;

create trigger trg_journal_entry_immutable
    before update or delete on journal_entry
    for each row execute function forbid_posted_entry_change();

create function forbid_posted_line_change() returns trigger
    language plpgsql as
$$
declare
    v_entry_id bigint := case when tg_op = 'INSERT' then new.entry_id else old.entry_id end;
begin
    if exists (select 1 from journal_entry where id = v_entry_id and status = 'POSTED') then
        raise exception '전기된 전표 %의 라인은 추가·수정·삭제할 수 없습니다', v_entry_id
            using errcode = 'restrict_violation';
    end if;
    return case when tg_op = 'DELETE' then old else new end;
end;
$$;

create trigger trg_journal_line_immutable
    before insert or update or delete on journal_line
    for each row execute function forbid_posted_line_change();

-- ---------------------------------------------------------------------------
-- 방어선 3: 마감된 회계기간에는 전표를 만들거나 옮길 수 없다 (불변식 3, 마감 기능은 5단계)
-- ---------------------------------------------------------------------------
create function forbid_closed_period_entry() returns trigger
    language plpgsql as
$$
begin
    if exists (select 1
                 from fiscal_period p
                 join fiscal_year y on y.id = p.fiscal_year_id
                where y.company_id = new.company_id
                  and new.entry_date between p.start_date and p.end_date
                  and p.status = 'CLOSED') then
        raise exception '마감된 기간(%)에는 전표를 만들 수 없습니다', new.entry_date
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;

create trigger trg_journal_entry_open_period
    before insert or update of entry_date on journal_entry
    for each row execute function forbid_closed_period_entry();
