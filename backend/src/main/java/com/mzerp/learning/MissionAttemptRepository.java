package com.mzerp.learning;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface MissionAttemptRepository extends JpaRepository<MissionAttempt, Long> {

    List<MissionAttempt> findByEnrollmentId(Long enrollmentId);

    Optional<MissionAttempt> findByEnrollmentIdAndMissionKey(Long enrollmentId, String missionKey);

    Optional<MissionAttempt> findByEnrollmentIdAndStatus(Long enrollmentId, MissionAttempt.Status status);
}
