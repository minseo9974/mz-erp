package com.mzerp.master.account;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    public record AccountResponse(String code, String name, AccountCategory category, String subgroup,
                                  NormalBalance normalBalance, String contraOfCode, boolean requiresPartner,
                                  StatementType statement, boolean system, boolean active) {
        static AccountResponse of(Account a, Map<Long, String> codeById) {
            return new AccountResponse(a.getCode(), a.getName(), a.getCategory(), a.getSubgroup(),
                    a.getNormalBalance(), a.getContraOfId() == null ? null : codeById.get(a.getContraOfId()),
                    a.isRequiresPartner(), a.getStatement(), a.isSystem(), a.isActive());
        }
    }

    public record CreateAccountRequest(
            @NotBlank @Pattern(regexp = "\\d{3,4}", message = "숫자 3~4자리") String code,
            @NotBlank @Size(max = 50) String name,
            @NotNull AccountCategory category,
            @NotBlank @Size(max = 30) String subgroup,
            @NotNull NormalBalance normalBalance,
            String contraOfCode,
            Boolean requiresPartner, // Jackson 3은 빠진 기본형(boolean)을 오류로 본다 → 래퍼 타입, 없으면 false
            @NotNull StatementType statement) {
    }

    public record UpdateAccountRequest(@Size(max = 50) String name, @Size(max = 30) String subgroup,
                                       Boolean requiresPartner, Boolean active) {
    }

    @GetMapping
    @PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")
    public List<AccountResponse> search(@PathVariable Long companyId,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) AccountCategory category,
                                        @RequestParam(defaultValue = "false") boolean activeOnly) {
        Map<Long, String> codes = service.codeById(companyId);
        return service.search(companyId, q, category, activeOnly).stream()
                .map(a -> AccountResponse.of(a, codes)).toList();
    }

    @GetMapping("/{code}")
    @PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")
    public AccountResponse get(@PathVariable Long companyId, @PathVariable String code) {
        return AccountResponse.of(service.get(companyId, code), service.codeById(companyId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@companyAccess.has(#companyId, 'ADMIN')")
    public AccountResponse create(@PathVariable Long companyId, @Valid @RequestBody CreateAccountRequest r) {
        Account created = service.create(companyId, new AccountService.CreateCommand(r.code(), r.name(), r.category(),
                r.subgroup(), r.normalBalance(), r.contraOfCode(), Boolean.TRUE.equals(r.requiresPartner()), r.statement()));
        return AccountResponse.of(created, service.codeById(companyId));
    }

    @PatchMapping("/{code}")
    @PreAuthorize("@companyAccess.has(#companyId, 'ADMIN')")
    public AccountResponse update(@PathVariable Long companyId, @PathVariable String code,
                                  @Valid @RequestBody UpdateAccountRequest r) {
        Account updated = service.update(companyId, code,
                new AccountService.UpdateCommand(r.name(), r.subgroup(), r.requiresPartner(), r.active()));
        return AccountResponse.of(updated, service.codeById(companyId));
    }
}
