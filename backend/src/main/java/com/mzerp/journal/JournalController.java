package com.mzerp.journal;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.mzerp.identity.AppUserPrincipal;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 일반전표 API (docs/design/api.md §2).
 * 작성·수정·삭제는 WRITER, 전기·역분개는 APPROVER 이상 (ADR-0008).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/journal-entries")
public class JournalController {

    private final JournalService service;

    public JournalController(JournalService service) {
        this.service = service;
    }

    public record LineRequest(@NotNull Side side, @NotNull String accountCode, String partnerCode,
                              @NotNull Long amount, @Size(max = 200) String memo) {
        JournalService.LineInput toInput() {
            return new JournalService.LineInput(side, accountCode, partnerCode, amount, memo);
        }
    }

    public record EntryRequest(@NotNull LocalDate entryDate, @Size(max = 200) String description,
                               @NotNull @Valid List<LineRequest> lines) {
        JournalService.EntryInput toInput() {
            return new JournalService.EntryInput(entryDate, description,
                    lines.stream().map(LineRequest::toInput).toList());
        }
    }

    public record UpdateRequest(@NotNull Integer version, @NotNull LocalDate entryDate,
                                @Size(max = 200) String description, @NotNull @Valid List<LineRequest> lines) {
    }

    public record PostAllRequest(@NotEmpty List<Long> ids) {
    }

    public record ReverseRequest(LocalDate reversalDate, @Size(max = 200) String reason) {
    }

    /** 기간 조회. 비우면 이번 달. */
    @GetMapping
    @PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")
    public List<JournalService.EntryView> list(@PathVariable Long companyId,
                                               @RequestParam(required = false) LocalDate from,
                                               @RequestParam(required = false) LocalDate to,
                                               @RequestParam(required = false) EntryStatus status,
                                               @RequestParam(required = false) EntryType type) {
        YearMonth thisMonth = YearMonth.now();
        return service.list(companyId,
                from != null ? from : thisMonth.atDay(1),
                to != null ? to : thisMonth.atEndOfMonth(),
                status, type);
    }

    @GetMapping("/{entryId}")
    @PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")
    public JournalService.EntryView get(@PathVariable Long companyId, @PathVariable Long entryId) {
        return service.get(companyId, entryId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")
    public JournalService.EntryView create(@PathVariable Long companyId,
                                           @AuthenticationPrincipal AppUserPrincipal user,
                                           @Valid @RequestBody EntryRequest request) {
        return service.create(companyId, user.userId(), request.toInput());
    }

    @PutMapping("/{entryId}")
    @PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")
    public JournalService.EntryView update(@PathVariable Long companyId, @PathVariable Long entryId,
                                           @Valid @RequestBody UpdateRequest request) {
        EntryRequest body = new EntryRequest(request.entryDate(), request.description(), request.lines());
        return service.update(companyId, entryId, request.version(), body.toInput());
    }

    @DeleteMapping("/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")
    public void delete(@PathVariable Long companyId, @PathVariable Long entryId) {
        service.delete(companyId, entryId);
    }

    @PostMapping("/{entryId}/post")
    @PreAuthorize("@companyAccess.has(#companyId, 'APPROVER')")
    public JournalService.EntryView post(@PathVariable Long companyId, @PathVariable Long entryId,
                                         @AuthenticationPrincipal AppUserPrincipal user) {
        return service.post(companyId, entryId, user.userId());
    }

    @PostMapping("/post")
    @PreAuthorize("@companyAccess.has(#companyId, 'APPROVER')")
    public List<JournalService.EntryView> postAll(@PathVariable Long companyId,
                                                  @AuthenticationPrincipal AppUserPrincipal user,
                                                  @Valid @RequestBody PostAllRequest request) {
        return service.postAll(companyId, request.ids(), user.userId());
    }

    @PostMapping("/{entryId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@companyAccess.has(#companyId, 'APPROVER')")
    public JournalService.EntryView reverse(@PathVariable Long companyId, @PathVariable Long entryId,
                                            @AuthenticationPrincipal AppUserPrincipal user,
                                            @Valid @RequestBody(required = false) ReverseRequest request) {
        ReverseRequest r = request == null ? new ReverseRequest(null, null) : request;
        return service.reverse(companyId, entryId, user.userId(), r.reversalDate(), r.reason());
    }
}
