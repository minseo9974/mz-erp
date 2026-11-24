-- L1: 제출 기록에 제출 값(JSON) 컬럼 추가 — quiz·lookup 오답 분포 분석용 (ADR-0017)
-- 전표 미션(entry·correction)은 본문 답을 보내지 않으므로 null을 허용한다.
alter table attempt_submission
    add column submitted_answer text;
