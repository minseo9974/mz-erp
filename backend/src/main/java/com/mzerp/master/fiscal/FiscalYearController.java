package com.mzerp.master.fiscal;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/fiscal-years")
public class FiscalYearController {

    private final FiscalYearService service;

    public FiscalYearController(FiscalYearService service) {
        this.service = service;
    }

    public record CreateFiscalYearRequest(@NotNull @Min(2000) @Max(2100) Integer year,
                                          @Min(1) @Max(12) Integer startMonth,
                                          @Min(1) Integer termNo) {
    }

    public record PeriodResponse(int periodNo, LocalDate startDate, LocalDate endDate, FiscalStatus status) {
    }

    public record FiscalYearResponse(int termNo, LocalDate startDate, LocalDate endDate, FiscalStatus status,
                                     List<PeriodResponse> periods) {
        static FiscalYearResponse of(FiscalYear y) {
            return new FiscalYearResponse(y.getTermNo(), y.getStartDate(), y.getEndDate(), y.getStatus(),
                    y.getPeriods().stream()
                            .map(p -> new PeriodResponse(p.getPeriodNo(), p.getStartDate(), p.getEndDate(), p.getStatus()))
                            .toList());
        }
    }

    @GetMapping
    @PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")
    public List<FiscalYearResponse> list(@PathVariable Long companyId) {
        return service.list(companyId).stream().map(FiscalYearResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@companyAccess.has(#companyId, 'ADMIN')")
    public FiscalYearResponse create(@PathVariable Long companyId, @Valid @RequestBody CreateFiscalYearRequest r) {
        int startMonth = r.startMonth() == null ? 1 : r.startMonth();
        return FiscalYearResponse.of(service.create(companyId,
                new FiscalYearService.CreateCommand(r.year(), startMonth, r.termNo())));
    }
}
