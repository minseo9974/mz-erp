-- 1단계: 사용자·권한, 회사, 회계연도·기간, 계정과목, 거래처
-- 설계: docs/design/erd.md

create table company (
    id          bigserial primary key,
    name        varchar(100) not null,
    biz_reg_no  varchar(20),
    industry    varchar(100),
    created_at  timestamptz  not null default now()
);

create table app_user (
    id            bigserial primary key,
    login_id      varchar(50)  not null unique,
    password_hash varchar(200) not null,
    name          varchar(50)  not null,
    is_active     boolean      not null default true,
    created_at    timestamptz  not null default now()
);

create table user_company_role (
    id         bigserial primary key,
    user_id    bigint      not null references app_user (id),
    company_id bigint      not null references company (id),
    role       varchar(20) not null check (role in ('WRITER', 'APPROVER', 'ADMIN')),
    unique (user_id, company_id)
);

create table fiscal_year (
    id         bigserial primary key,
    company_id bigint      not null references company (id),
    term_no    int         not null check (term_no > 0),
    start_date date        not null,
    end_date   date        not null,
    status     varchar(10) not null default 'OPEN' check (status in ('OPEN', 'CLOSED')),
    check (end_date > start_date),
    unique (company_id, start_date),
    unique (company_id, term_no)
);

create table fiscal_period (
    id             bigserial primary key,
    fiscal_year_id bigint      not null references fiscal_year (id),
    period_no      int         not null check (period_no between 1 and 12),
    start_date     date        not null,
    end_date       date        not null,
    status         varchar(10) not null default 'OPEN' check (status in ('OPEN', 'CLOSED')),
    closed_at      timestamptz,
    closed_by      bigint references app_user (id),
    check (end_date >= start_date),
    unique (fiscal_year_id, period_no)
);

create table account (
    id               bigserial primary key,
    company_id       bigint      not null references company (id),
    code             varchar(10) not null,
    name             varchar(50) not null,
    category         varchar(10) not null check (category in ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    subgroup         varchar(30) not null,
    normal_balance   varchar(6)  not null check (normal_balance in ('DEBIT', 'CREDIT')),
    contra_of_id     bigint references account (id),
    requires_partner boolean     not null default false,
    statement        varchar(3)  not null check (statement in ('BS', 'IS', 'MC', 'SYS')),
    is_system        boolean     not null default false,
    is_active        boolean     not null default true,
    unique (company_id, code)
);

create table partner (
    id           bigserial primary key,
    company_id   bigint       not null references company (id),
    code         varchar(10)  not null,
    name         varchar(100) not null,
    biz_reg_no   varchar(20),
    partner_type varchar(10)  not null check (partner_type in ('CUSTOMER', 'VENDOR', 'BOTH', 'BANK', 'CARD', 'ETC')),
    is_active    boolean      not null default true,
    unique (company_id, code)
);

create index ix_user_company_role_company on user_company_role (company_id);
create index ix_fiscal_period_year on fiscal_period (fiscal_year_id);
