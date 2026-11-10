package com.mzerp.master.account;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mzerp.common.error.ApiException;
import com.mzerp.common.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public record CreateCommand(String code, String name, AccountCategory category, String subgroup,
                                NormalBalance normalBalance, String contraOfCode, boolean requiresPartner,
                                StatementType statement) {
    }

    public record UpdateCommand(String name, String subgroup, Boolean requiresPartner, Boolean active) {
    }

    /** 새 회사에 표준 계정과목표를 복사한다. 차감계정 연결은 모든 계정을 만든 뒤 두 번째로 처리한다. */
    @Transactional
    public void copyStandardChart(Long companyId) {
        List<StandardChart.Row> rows = StandardChart.load();
        Map<String, Account> byCode = new HashMap<>();
        for (StandardChart.Row r : rows) {
            Account saved = accounts.save(new Account(companyId, r.code(), r.name(), r.category(), r.subgroup(),
                    r.normalBalance(), r.requiresPartner(), r.statement(), r.system()));
            byCode.put(r.code(), saved);
        }
        for (StandardChart.Row r : rows) {
            if (r.contraOf() != null) {
                Account target = byCode.get(r.contraOf());
                if (target == null) {
                    throw new IllegalStateException("accounts.csv: " + r.code() + "의 차감 대상 " + r.contraOf() + "가 없습니다");
                }
                byCode.get(r.code()).markContraOf(target);
            }
        }
    }

    /**
     * 계정 검색 (화면의 F2 코드 도움). 회사당 계정은 100~200개 수준이라 메모리에서 거른다.
     * q는 코드 앞자리 또는 이름 일부와 맞춰 본다.
     */
    @Transactional(readOnly = true)
    public List<Account> search(Long companyId, String q, AccountCategory category, boolean activeOnly) {
        String keyword = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        return accounts.findByCompanyIdOrderByCode(companyId).stream()
                .filter(a -> keyword.isEmpty() || a.getCode().startsWith(keyword)
                        || a.getName().toLowerCase(Locale.ROOT).contains(keyword))
                .filter(a -> category == null || a.getCategory() == category)
                .filter(a -> !activeOnly || a.isActive())
                .toList();
    }

    @Transactional(readOnly = true)
    public Account get(Long companyId, String code) {
        return accounts.findByCompanyIdAndCode(companyId, code)
                .orElseThrow(() -> ApiException.notFound("계정 " + code));
    }

    @Transactional
    public Account create(Long companyId, CreateCommand c) {
        if (accounts.existsByCompanyIdAndCode(companyId, c.code())) {
            throw new ApiException(ErrorCode.DUPLICATE_CODE, "계정코드 " + c.code() + "은(는) 이미 있습니다");
        }
        Account account = accounts.save(new Account(companyId, c.code(), c.name(), c.category(), c.subgroup(),
                c.normalBalance(), c.requiresPartner(), c.statement(), false));
        if (c.contraOfCode() != null && !c.contraOfCode().isBlank()) {
            account.markContraOf(get(companyId, c.contraOfCode()));
        }
        return account;
    }

    @Transactional
    public Account update(Long companyId, String code, UpdateCommand c) {
        Account account = get(companyId, code);
        account.update(c.name(), c.subgroup(), c.requiresPartner(), c.active());
        return account;
    }

    /** 응답에 차감 대상 코드를 함께 내려주기 위한 id → code 맵. */
    @Transactional(readOnly = true)
    public Map<Long, String> codeById(Long companyId) {
        Map<Long, String> map = new HashMap<>();
        accounts.findByCompanyIdOrderByCode(companyId).forEach(a -> map.put(a.getId(), a.getCode()));
        return map;
    }
}
