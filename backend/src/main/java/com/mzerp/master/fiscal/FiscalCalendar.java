package com.mzerp.master.fiscal;

import java.time.LocalDate;

import com.mzerp.common.error.ApiException;
import com.mzerp.common.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 날짜 → 회계연도·기간. 전표 모듈이 "이 날짜에 전표를 만들 수 있는가"를 물을 때 쓴다. */
@Service
public class FiscalCalendar {

    private final FiscalPeriodRepository periods;

    public FiscalCalendar(FiscalPeriodRepository periods) {
        this.periods = periods;
    }

    public record OpenPeriod(Long fiscalYearId, int termNo, int periodNo) {
    }

    /**
     * 전표를 쓸 수 있는 열린 기간을 돌려준다.
     * 회계연도가 없으면 FISCAL_PERIOD_NOT_FOUND, 마감됐으면 PERIOD_CLOSED (불변식 3).
     */
    @Transactional(readOnly = true)
    public OpenPeriod requireOpenPeriod(Long companyId, LocalDate date) {
        FiscalPeriod period = periods.findByCompanyAndDate(companyId, date)
                .orElseThrow(() -> new ApiException(ErrorCode.FISCAL_PERIOD_NOT_FOUND,
                        date + "이(가) 속한 회계연도가 없습니다. 회계연도를 먼저 만드세요"));
        if (period.getStatus() == FiscalStatus.CLOSED) {
            throw new ApiException(ErrorCode.PERIOD_CLOSED, date + "이(가) 속한 " + period.getPeriodNo() + "월은 마감되었습니다");
        }
        return new OpenPeriod(period.getFiscalYear().getId(), period.getFiscalYear().getTermNo(), period.getPeriodNo());
    }
}
