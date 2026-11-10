package com.mzerp.master.fiscal;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FiscalPeriodRepository extends JpaRepository<FiscalPeriod, Long> {

    @Query("""
            select p from FiscalPeriod p join fetch p.fiscalYear y
            where y.companyId = :companyId and :date between p.startDate and p.endDate
            """)
    Optional<FiscalPeriod> findByCompanyAndDate(@Param("companyId") Long companyId, @Param("date") LocalDate date);
}
