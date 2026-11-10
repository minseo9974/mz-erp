package com.mzerp.master.company;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mzerp.identity.AppUser;
import com.mzerp.identity.AppUserRepository;
import com.mzerp.identity.CompanyRole;
import com.mzerp.identity.UserCompanyRole;
import com.mzerp.identity.UserCompanyRoleRepository;
import com.mzerp.common.error.ApiException;
import com.mzerp.master.account.AccountService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyService {

    private final CompanyRepository companies;
    private final UserCompanyRoleRepository roles;
    private final AppUserRepository users;
    private final AccountService accounts;

    public CompanyService(CompanyRepository companies, UserCompanyRoleRepository roles,
                          AppUserRepository users, AccountService accounts) {
        this.companies = companies;
        this.roles = roles;
        this.users = users;
        this.accounts = accounts;
    }

    public record CreateCommand(String name, String bizRegNo, String industry) {
    }

    public record MyCompany(Long companyId, String name, CompanyRole role) {
    }

    public record Me(Long userId, String loginId, String name, List<MyCompany> companies) {
    }

    /** 회사를 만들고 표준 계정과목표를 복사한 뒤, 만든 사람을 ADMIN으로 지정한다. */
    @Transactional
    public Company create(Long creatorUserId, CreateCommand command) {
        Company company = companies.save(new Company(command.name(), command.bizRegNo(), command.industry()));
        accounts.copyStandardChart(company.getId());
        roles.save(new UserCompanyRole(creatorUserId, company.getId(), CompanyRole.ADMIN));
        return company;
    }

    @Transactional(readOnly = true)
    public Me me(Long userId) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("사용자"));
        List<UserCompanyRole> myRoles = roles.findByUserIdOrderByCompanyId(userId);
        Map<Long, Company> byId = companies.findAllById(myRoles.stream().map(UserCompanyRole::getCompanyId).toList())
                .stream().collect(Collectors.toMap(Company::getId, Function.identity()));
        List<MyCompany> mine = myRoles.stream()
                .map(r -> new MyCompany(r.getCompanyId(), byId.get(r.getCompanyId()).getName(), r.getRole()))
                .toList();
        return new Me(user.getId(), user.getLoginId(), user.getName(), mine);
    }
}
