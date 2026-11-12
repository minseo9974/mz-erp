-- L1: 끝낸 미션 다시 풀기 (연습)
-- 미션을 통과하면 그 거래는 이미 장부에 전기되어 있다. 다시 풀 때 전표를 또 전기하면 같은 거래가 두 번 기록되므로,
-- 다시 풀기는 "연습 시도"로 따로 기록하고 채점만 한다 — 연습으로 쓴 전표는 결재가 끝나면 지운다.
-- 그래서 한 미션에 시도가 여러 개일 수 있다: 본 시도 1개 + 연습 시도 n개.

alter table mission_attempt add column practice boolean not null default false;

alter table mission_attempt drop constraint mission_attempt_enrollment_id_mission_key_key;

-- 본 시도는 미션마다 하나
create unique index ux_mission_attempt_original on mission_attempt (enrollment_id, mission_key) where not practice;

-- 연습 시도도 "한 번에 하나만" 규칙을 따른다 (ux_mission_attempt_one_in_progress 가 본 시도·연습을 함께 센다)
