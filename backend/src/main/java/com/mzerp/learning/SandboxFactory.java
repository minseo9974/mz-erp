package com.mzerp.learning;

import java.util.UUID;

import com.mzerp.identity.AppUser;
import com.mzerp.identity.AppUserRepository;
import com.mzerp.identity.CompanyRole;
import com.mzerp.identity.UserCompanyRole;
import com.mzerp.identity.UserCompanyRoleRepository;
import com.mzerp.learning.scenario.Scenario;
import com.mzerp.learning.scenario.ScenarioReplayer;
import com.mzerp.master.company.CompanyService;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실습 회사를 만든다 (ADR-0014): 사용자마다 시나리오 회사를 한 벌씩.
 * <ul>
 *   <li>회사의 관리자·승인자는 <b>팀장 역할의 시스템 계정</b>이다. 채점을 통과한 전표를 이 계정이 전기한다</li>
 *   <li>실습자는 작성자(WRITER)다. 전표를 쓰고 고칠 수 있지만 직접 전기하지는 못한다 — 신입의 실제 권한과 같다</li>
 *   <li>회계연도·거래처·기초잔액은 리플레이어가 ERP 서비스로 넣는다</li>
 * </ul>
 */
@Component
class SandboxFactory {

    static final String TEAM_LEAD_LOGIN = "system.teamlead";

    private final AppUserRepository users;
    private final UserCompanyRoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final CompanyService companies;
    private final ScenarioReplayer replayer;

    SandboxFactory(AppUserRepository users, UserCompanyRoleRepository roles, PasswordEncoder passwordEncoder,
                   CompanyService companies, ScenarioReplayer replayer) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.companies = companies;
        this.replayer = replayer;
    }

    /** @return 새 실습 회사의 id */
    @Transactional
    Long create(Long learnerUserId, Scenario scenario) {
        Long teamLead = teamLeadId();
        Long companyId = companies.create(teamLead,
                new CompanyService.CreateCommand(scenario.companyName(), null, "제조업")).getId();
        roles.save(new UserCompanyRole(learnerUserId, companyId, CompanyRole.WRITER));
        replayer.prepare(companyId, teamLead, scenario);
        return companyId;
    }

    /** 팀장 시스템 계정. 처음 필요할 때 만든다. 비밀번호는 아무도 모르는 임의 값이라 로그인할 수 없다. */
    @Transactional
    Long teamLeadId() {
        return users.findByLoginId(TEAM_LEAD_LOGIN)
                .orElseGet(() -> users.save(new AppUser(TEAM_LEAD_LOGIN,
                        passwordEncoder.encode(UUID.randomUUID().toString()), "김 팀장")))
                .getId();
    }
}
