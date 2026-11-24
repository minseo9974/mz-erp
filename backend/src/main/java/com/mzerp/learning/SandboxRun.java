package com.mzerp.learning;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 실습 회사 회차(run) (ADR-0019 §1).
 * 수강(Enrollment) 하나가 여러 회차를 가질 수 있다. "이 장부터 다시"를 하면 새 실습 회사로 새 회차를 열고,
 * 이전 회차는 ABANDONED로 남긴다. 수강당 ACTIVE 회차는 하나뿐이다.
 */
@Entity
@Table(name = "sandbox_run")
public class SandboxRun {

    public enum Status {
        ACTIVE, ABANDONED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "enrollment_id", nullable = false, updatable = false)
    private Long enrollmentId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** 이 회차를 시작한 장 키. null이면 과정 처음부터 시작 */
    @Column(name = "start_chapter", updatable = false)
    private String startChapter;

    @Column(name = "course_version", nullable = false, updatable = false)
    private int courseVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    protected SandboxRun() {
    }

    SandboxRun(Long enrollmentId, Long companyId, String startChapter, int courseVersion) {
        this.enrollmentId = enrollmentId;
        this.companyId = companyId;
        this.startChapter = startChapter;
        this.courseVersion = courseVersion;
    }

    void abandon() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("진행 중인 회차만 종료할 수 있습니다 (" + status + ")");
        }
        this.status = Status.ABANDONED;
        this.endedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getEnrollmentId() { return enrollmentId; }
    public Long getCompanyId() { return companyId; }
    public String getStartChapter() { return startChapter; }
    public int getCourseVersion() { return courseVersion; }
    public Status getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
}
