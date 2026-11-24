-- L1: 실습 회사 회차(run) (ADR-0019 §1)
-- 수강(enrollment) 하나가 여러 회차(sandbox_run)를 갖는다.
-- "이 장부터 다시"를 하면 이전 회차와 진행 중 시도는 ABANDONED로 남기고, 새 실습 회사로 새 회차를 연다.

create table sandbox_run (
    id             bigserial   primary key,
    enrollment_id  bigint      not null references enrollment (id),
    company_id     bigint      not null unique references company (id),
    start_chapter  varchar(50),                                         -- null = 과정 처음부터
    course_version int         not null,                                -- 이 회차를 만들 때의 과정 버전
    status         varchar(12) not null check (status in ('ACTIVE', 'ABANDONED')),
    started_at     timestamptz not null default now(),
    ended_at       timestamptz,
    check ((status = 'ACTIVE') = (ended_at is null))
);

-- 수강당 진행 중인(ACTIVE) 회차는 하나뿐
create unique index ux_sandbox_run_one_active
    on sandbox_run (enrollment_id) where status = 'ACTIVE';

create index ix_sandbox_run_enrollment on sandbox_run (enrollment_id);

-- 기존 수강마다 첫 회차(start_chapter = null, ACTIVE)를 만들어 회사를 옮긴다
insert into sandbox_run (enrollment_id, company_id, start_chapter, course_version, status, started_at)
select id, company_id, null, course_version, 'ACTIVE', started_at
  from enrollment;

-- 미션 시도는 회차를 가리킨다
alter table mission_attempt add column run_id bigint references sandbox_run (id);

update mission_attempt a
   set run_id = r.id
  from sandbox_run r
 where r.enrollment_id = a.enrollment_id;

alter table mission_attempt alter column run_id set not null;
create index ix_mission_attempt_run on mission_attempt (run_id);

-- 본 시도 유니크: (수강, 미션) → (회차, 미션)
-- "진행 중 하나"(ux_mission_attempt_one_in_progress)는 수강 단위 그대로 둔다
drop index ux_mission_attempt_original;
create unique index ux_mission_attempt_original
    on mission_attempt (run_id, mission_key) where not practice;

-- 다시 시작할 때 진행 중이던 시도는 ABANDONED로 끝낸다 (finished_at 필수 제약은 기존 check가 그대로 지킨다)
alter table mission_attempt drop constraint mission_attempt_status_check;
alter table mission_attempt add constraint mission_attempt_status_check
    check (status in ('IN_PROGRESS', 'PASSED', 'REVEALED', 'ABANDONED'));

-- 회사는 회차의 것이다 (수강에서 제거)
alter table enrollment drop column company_id;
