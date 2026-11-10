package com.mzerp.master.fiscal;

import java.time.LocalDate;
import java.util.List;

import com.mzerp.common.error.ApiException;
import com.mzerp.common.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalYearService {

    private final FiscalYearRepository years;

    public FiscalYearService(FiscalYearRepository years) {
        this.years = years;
    }

    /**
     * @param termNo 기수. 비우면 직전 기수 + 1, 첫 회계연도면 1
     */
    public record CreateCommand(int year, int startMonth, Integer termNo) {
    }

    @Transactional(readOnly = true)
    public List<FiscalYear> list(Long companyId) {
        List<FiscalYear> list = years.findByCompanyIdOrderByStartDate(companyId);
        list.forEach(FiscalYear::getPeriods); // 트랜잭션 안에서 기간까지 읽어 둔다 (open-in-view 꺼짐)
        return list;
    }

    @Transactional
    public FiscalYear create(Long companyId, CreateCommand c) {
        LocalDate start = LocalDate.of(c.year(), c.startMonth(), 1);
        LocalDate end = start.plusYears(1).minusDays(1);
        if (years.existsByCompanyIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(companyId, end, start)) {
            throw new ApiException(ErrorCode.FISCAL_YEAR_OVERLAP, start + " ~ " + end + " 기간이 기존 회계연도와 겹칩니다");
        }
        int termNo = c.termNo() != null ? c.termNo()
                : years.findTopByCompanyIdOrderByTermNoDesc(companyId).map(y -> y.getTermNo() + 1).orElse(1);
        if (years.existsByCompanyIdAndTermNo(companyId, termNo)) {
            throw new ApiException(ErrorCode.DUPLICATE_CODE, "제" + termNo + "기는 이미 있습니다");
        }
        FiscalYear saved = years.save(FiscalYear.open(companyId, termNo, start));
        saved.getPeriods();
        return saved;
    }
}
