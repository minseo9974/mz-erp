package com.mzerp.learning;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    Optional<Enrollment> findByUserIdAndCourseKey(Long userId, String courseKey);

    Optional<Enrollment> findByIdAndUserId(Long id, Long userId);
}
