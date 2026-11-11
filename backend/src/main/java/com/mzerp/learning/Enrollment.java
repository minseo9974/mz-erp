package com.mzerp.learning;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 수강: 사용자 한 명이 과정 하나를 자기만의 실습 회사에서 진행한다 (ADR-0014). */
@Entity
@Table(name = "enrollment")
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "course_key", nullable = false, updatable = false)
    private String courseKey;

    @Column(name = "course_version", nullable = false)
    private int courseVersion;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Enrollment() {
    }

    Enrollment(Long userId, Long companyId, String courseKey, int courseVersion) {
        this.userId = userId;
        this.companyId = companyId;
        this.courseKey = courseKey;
        this.courseVersion = courseVersion;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getCompanyId() { return companyId; }
    public String getCourseKey() { return courseKey; }
    public int getCourseVersion() { return courseVersion; }
}
