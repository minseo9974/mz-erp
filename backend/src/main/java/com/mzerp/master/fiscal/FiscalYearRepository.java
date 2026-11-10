package com.mzerp.master.fiscal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalYearRepository extends JpaRepository<FiscalYear, Long> {

    List<FiscalYear> findByCompanyIdOrderByStartDate(Long companyId);

    Optional<FiscalYear> findTopByCompanyIdOrderByTermNoDesc(Long companyId);

    /** [start, end]와 겹치는 회계연도가 있는지: 기존.start ≤ 새.end 그리고 기존.end ≥ 새.start */
    boolean existsByCompanyIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long companyId, LocalDate newEnd, LocalDate newStart);

    boolean existsByCompanyIdAndTermNo(Long companyId, int termNo);
}
