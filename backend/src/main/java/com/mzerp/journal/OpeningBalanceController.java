package com.mzerp.journal;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.mzerp.identity.AppUserPrincipal;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 전기분 재무상태표 API (docs/design/api.md §1). {year}는 회계연도가 시작하는 해.
 * 저장은 임시 전표를 만들거나 바꾼다. 전기·삭제·역분개는 일반전표 API를 그대로 쓴다
 * ({@code POST /journal-entries/{id}/post} 등) — 기초잔액도 전표이기 때문.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/fiscal-years/{year}/opening-balance")
public class OpeningBalanceController {

    private final OpeningBalanceService service;

    public OpeningBalanceController(OpeningBalanceService service) {
        this.service = service;
    }

    public record SaveRequest(@NotNull @Valid List<JournalController.LineRequest> lines) {
    }

    @GetMapping
    @PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")
    public OpeningBalanceService.OpeningView get(@PathVariable Long companyId, @PathVariable int year) {
        return service.get(companyId, year);
    }

    @PutMapping
    @PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")
    public OpeningBalanceService.OpeningView save(@PathVariable Long companyId, @PathVariable int year,
                                                  @AuthenticationPrincipal AppUserPrincipal user,
                                                  @Valid @RequestBody SaveRequest request) {
        return service.save(companyId, user.userId(), year,
                request.lines().stream().map(JournalController.LineRequest::toInput).toList());
    }
}
