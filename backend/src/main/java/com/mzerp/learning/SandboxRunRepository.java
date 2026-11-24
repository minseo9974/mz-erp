package com.mzerp.learning;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SandboxRunRepository extends JpaRepository<SandboxRun, Long> {

    Optional<SandboxRun> findByEnrollmentIdAndStatus(Long enrollmentId, SandboxRun.Status status);

    List<SandboxRun> findByEnrollmentIdOrderById(Long enrollmentId);
}
