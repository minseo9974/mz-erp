package com.mzerp.learning;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface MissionAttemptRepository extends JpaRepository<MissionAttempt, Long> {

    List<MissionAttempt> findByEnrollmentIdOrderById(Long enrollmentId);

    List<MissionAttempt> findByRunId(Long runId);

    /** 이 회차의 본 시도 (회차·미션마다 하나) */
    Optional<MissionAttempt> findByRunIdAndMissionKeyAndPracticeFalse(Long runId, String missionKey);

    /** 이 회차의 본 시도와 연습 시도 전부, 시작한 순서대로 */
    List<MissionAttempt> findByRunIdAndMissionKeyOrderById(Long runId, String missionKey);

    /** 수강 전체에서 이 미션의 본 시도 (최신순) — 이전 회차에서 마지막으로 끝낸 기록을 찾을 때 */
    List<MissionAttempt> findByEnrollmentIdAndMissionKeyAndPracticeFalseOrderByIdDesc(Long enrollmentId, String missionKey);

    /** 수강 전체에서 이 미션의 본 시도와 연습 시도 전부, 시작한 순서대로 */
    List<MissionAttempt> findByEnrollmentIdAndMissionKeyOrderById(Long enrollmentId, String missionKey);

    /** 수강 전체에서 이 미션의 본 시도를 시작한 적이 있는가 (장 다시 시작 가능 여부) */
    boolean existsByEnrollmentIdAndMissionKeyAndPracticeFalse(Long enrollmentId, String missionKey);

    Optional<MissionAttempt> findByEnrollmentIdAndStatus(Long enrollmentId, MissionAttempt.Status status);
}
