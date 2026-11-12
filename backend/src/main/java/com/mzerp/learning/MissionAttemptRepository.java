package com.mzerp.learning;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface MissionAttemptRepository extends JpaRepository<MissionAttempt, Long> {

    List<MissionAttempt> findByEnrollmentId(Long enrollmentId);

    /** 본 시도 (미션마다 하나) */
    Optional<MissionAttempt> findByEnrollmentIdAndMissionKeyAndPracticeFalse(Long enrollmentId, String missionKey);

    /** 본 시도와 연습 시도 전부, 시작한 순서대로 */
    List<MissionAttempt> findByEnrollmentIdAndMissionKeyOrderById(Long enrollmentId, String missionKey);

    Optional<MissionAttempt> findByEnrollmentIdAndStatus(Long enrollmentId, MissionAttempt.Status status);
}
