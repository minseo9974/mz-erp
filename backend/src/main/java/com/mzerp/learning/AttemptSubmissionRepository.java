package com.mzerp.learning;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface AttemptSubmissionRepository extends JpaRepository<AttemptSubmission, Long> {

    List<AttemptSubmission> findByAttemptIdOrderById(Long attemptId);
}
