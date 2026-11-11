-- L1: 실습 층 — 수강, 미션 시도, 제출 기록 (docs/design/training-site.md, ADR-0012~0015)
-- 과정·미션 내용은 저장소 파일(data/course)에 있고, 여기에는 사용자의 진행 상태만 둔다.
-- ERP 테이블에는 실습용 컬럼을 추가하지 않는다.

-- ---------------------------------------------------------------------------
-- 수강: 사용자 한 명이 과정 하나를 자기만의 실습 회사에서 진행한다 (ADR-0014)
-- ---------------------------------------------------------------------------
create table enrollment (
    id             bigserial primary key,
    user_id        bigint      not null references app_user (id),
    company_id     bigint      not null unique references company (id), -- 실습 회사는 수강 하나의 것
    course_key     varchar(50) not null,
    course_version int         not null,                                -- 시작할 때의 콘텐츠 버전
    started_at     timestamptz not null default now(),
    completed_at   timestamptz,
    unique (user_id, course_key)
);

-- ---------------------------------------------------------------------------
-- 미션 시도: 시작할 때의 장부 잔액을 저장해 두고, 제출할 때 지금 잔액과의 차이로 채점한다 (ADR-0013)
-- ---------------------------------------------------------------------------
create table mission_attempt (
    id            bigserial primary key,
    enrollment_id bigint      not null references enrollment (id),
    company_id    bigint      not null references company (id),
    mission_key   varchar(80) not null,
    status        varchar(12) not null check (status in ('IN_PROGRESS', 'PASSED', 'REVEALED')),
    baseline      text        not null,           -- 시작 시점의 계정·거래처별 순잔액 (JSON)
    hints_used    int         not null default 0,
    submissions   int         not null default 0,
    started_at    timestamptz not null default now(),
    finished_at   timestamptz,
    unique (enrollment_id, mission_key),
    check ((status = 'IN_PROGRESS') = (finished_at is null))
);

-- 한 번에 미션 하나만: 두 미션을 동시에 진행하면 장부 변화가 섞여 채점할 수 없다
create unique index ux_mission_attempt_one_in_progress on mission_attempt (enrollment_id) where status = 'IN_PROGRESS';

-- ---------------------------------------------------------------------------
-- 제출 기록: 결재 요청마다 한 줄. 어떤 미션에서 어떤 오답이 많이 나오는지 보는 재료
-- ---------------------------------------------------------------------------
create table attempt_submission (
    id           bigserial primary key,
    attempt_id   bigint      not null references mission_attempt (id),
    company_id   bigint      not null references company (id),
    submitted_at timestamptz not null default now(),
    passed       boolean     not null,
    findings     text        not null              -- 반려 사유 목록 (JSON)
);

create index ix_attempt_submission_attempt on attempt_submission (attempt_id);
