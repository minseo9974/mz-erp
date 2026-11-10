package com.mzerp.master.fiscal;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 회계기간(월). 마감 단위이며, 마감된 기간에는 전표를 만들 수 없다 (CLAUDE.md 불변식 3, 5단계에서 구현). */
@Entity
@Table(name = "fiscal_period")
public class FiscalPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fiscal_year_id", nullable = false, updatable = false)
    private FiscalYear fiscalYear;

    @Column(name = "period_no", nullable = false, updatable = false)
    private int periodNo;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false, updatable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FiscalStatus status = FiscalStatus.OPEN;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    protected FiscalPeriod() {
    }

    FiscalPeriod(FiscalYear fiscalYear, int periodNo, LocalDate startDate, LocalDate endDate) {
        this.fiscalYear = fiscalYear;
        this.periodNo = periodNo;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public Long getId() { return id; }
    public FiscalYear getFiscalYear() { return fiscalYear; }
    public int getPeriodNo() { return periodNo; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public FiscalStatus getStatus() { return status; }
    public Instant getClosedAt() { return closedAt; }
}
