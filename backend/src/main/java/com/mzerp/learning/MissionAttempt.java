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

import com.mzerp.common.error.ApiException;
import com.mzerp.common.error.ErrorCode;

/**
 * 미션 시도. 시작할 때의 장부 잔액(baseline)을 품고 있다가, 제출할 때 지금 잔액과의 차이로 채점된다 (ADR-0013).
 * 끝나는 길은 둘: 채점 통과(PASSED), 정답 보고 넘어가기(REVEALED).
 */
@Entity
@Table(name = "mission_attempt")
public class MissionAttempt {

    public enum Status {
        IN_PROGRESS, PASSED, REVEALED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "enrollment_id", nullable = false, updatable = false)
    private Long enrollmentId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "mission_key", nullable = false, updatable = false)
    private String missionKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.IN_PROGRESS;

    /** 시작 시점의 계정·거래처별 순잔액 (JSON: {"108|00101": 12000000, …}) */
    @Column(nullable = false, updatable = false)
    private String baseline;

    @Column(name = "hints_used", nullable = false)
    private int hintsUsed;

    @Column(nullable = false)
    private int submissions;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected MissionAttempt() {
    }

    MissionAttempt(Long enrollmentId, Long companyId, String missionKey, String baseline) {
        this.enrollmentId = enrollmentId;
        this.companyId = companyId;
        this.missionKey = missionKey;
        this.baseline = baseline;
    }

    void requireInProgress() {
        if (status != Status.IN_PROGRESS) {
            throw new ApiException(ErrorCode.MISSION_ALREADY_FINISHED, "이 미션은 이미 끝났습니다 (" + status + ")");
        }
    }

    void countSubmission() {
        requireInProgress();
        submissions++;
    }

    /** 다음 힌트를 연다. 더 열 힌트가 없으면 그대로. */
    void useHint(int available) {
        requireInProgress();
        if (hintsUsed < available) {
            hintsUsed++;
        }
    }

    void finish(Status result) {
        requireInProgress();
        this.status = result;
        this.finishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getEnrollmentId() { return enrollmentId; }
    public Long getCompanyId() { return companyId; }
    public String getMissionKey() { return missionKey; }
    public Status getStatus() { return status; }
    public String getBaseline() { return baseline; }
    public int getHintsUsed() { return hintsUsed; }
    public int getSubmissions() { return submissions; }
}
