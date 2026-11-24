package com.mzerp.learning;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 결재 요청 한 번의 기록: 통과했는지, 반려됐다면 사유가 무엇이었는지. 미션별 오답 분석의 재료다. */
@Entity
@Table(name = "attempt_submission")
public class AttemptSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false, updatable = false)
    private Long attemptId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt = Instant.now();

    @Column(nullable = false, updatable = false)
    private boolean passed;

    /** 반려 사유 목록 (JSON) */
    @Column(nullable = false, updatable = false)
    private String findings;

    /** 제출한 값 (JSON). quiz·lookup처럼 본문으로 답을 보낸 경우에만 채워지고, 전표 미션은 null (ADR-0017) */
    @Column(name = "submitted_answer", updatable = false)
    private String submittedAnswer;

    protected AttemptSubmission() {
    }

    AttemptSubmission(Long attemptId, Long companyId, boolean passed, String findings, String submittedAnswer) {
        this.attemptId = attemptId;
        this.companyId = companyId;
        this.passed = passed;
        this.findings = findings;
        this.submittedAnswer = submittedAnswer;
    }

    public boolean isPassed() { return passed; }
    public String getFindings() { return findings; }
    public String getSubmittedAnswer() { return submittedAnswer; }
}
