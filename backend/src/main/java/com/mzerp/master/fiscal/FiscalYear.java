package com.mzerp.master.fiscal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/** 회계연도(기수). 만들 때 12개월 회계기간이 함께 생긴다. */
@Entity
@Table(name = "fiscal_year")
public class FiscalYear {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "term_no", nullable = false, updatable = false)
    private int termNo;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false, updatable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FiscalStatus status = FiscalStatus.OPEN;

    @OneToMany(mappedBy = "fiscalYear", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FiscalPeriod> periods = new ArrayList<>();

    protected FiscalYear() {
    }

    /** startDate부터 1년. 예: 2026-01-01 → 2026-12-31, 기간 1~12월. 3월 결산법인이면 2026-04-01부터. */
    public static FiscalYear open(Long companyId, int termNo, LocalDate startDate) {
        FiscalYear year = new FiscalYear();
        year.companyId = companyId;
        year.termNo = termNo;
        year.startDate = startDate;
        year.endDate = startDate.plusYears(1).minusDays(1);
        for (int i = 0; i < 12; i++) {
            LocalDate from = startDate.plusMonths(i);
            year.periods.add(new FiscalPeriod(year, i + 1, from, from.plusMonths(1).minusDays(1)));
        }
        return year;
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public int getTermNo() { return termNo; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public FiscalStatus getStatus() { return status; }

    public List<FiscalPeriod> getPeriods() {
        return periods.stream().sorted(Comparator.comparingInt(FiscalPeriod::getPeriodNo)).toList();
    }
}
