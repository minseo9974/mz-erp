package com.mzerp.master.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.mzerp.common.error.ApiException;
import com.mzerp.common.error.ErrorCode;

/**
 * 계정과목. 속성이 시스템의 행동을 정한다 — 코드에 계정코드를 하드코딩하지 않는다 (CLAUDE.md 불변식 6).
 * 대분류·정상잔액은 생성 후 바꿀 수 없다: 이미 전표가 붙은 계정의 잔액 방향이 바뀌면 과거 장부가 전부 틀어진다.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AccountCategory category;

    @Column(nullable = false)
    private String subgroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false, updatable = false)
    private NormalBalance normalBalance;

    @Column(name = "contra_of_id")
    private Long contraOfId;

    @Column(name = "requires_partner", nullable = false)
    private boolean requiresPartner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatementType statement;

    @Column(name = "is_system", nullable = false, updatable = false)
    private boolean system;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Account() {
    }

    public Account(Long companyId, String code, String name, AccountCategory category, String subgroup,
                   NormalBalance normalBalance, boolean requiresPartner, StatementType statement, boolean system) {
        this.companyId = companyId;
        this.code = code;
        this.name = name;
        this.category = category;
        this.subgroup = subgroup;
        this.normalBalance = normalBalance;
        this.requiresPartner = requiresPartner;
        this.statement = statement;
        this.system = system;
    }

    /** 차감계정(대손충당금 등)으로 지정. 같은 회사의 계정이어야 하고 정상잔액 방향이 반대여야 한다. */
    public void markContraOf(Account target) {
        if (!target.companyId.equals(companyId) || target.normalBalance == normalBalance || target.id.equals(id)) {
            throw new ApiException(ErrorCode.ACCOUNT_CONTRA_INVALID,
                    code + " " + name + "은(는) " + target.code + "의 차감계정이 될 수 없습니다");
        }
        this.contraOfId = target.id;
    }

    public void update(String name, String subgroup, Boolean requiresPartner, Boolean active) {
        if (system) {
            throw new ApiException(ErrorCode.ACCOUNT_SYSTEM_LOCKED, code + " " + this.name + "은(는) 시스템 계정입니다");
        }
        if (name != null) this.name = name;
        if (subgroup != null) this.subgroup = subgroup;
        if (requiresPartner != null) this.requiresPartner = requiresPartner;
        if (active != null) this.active = active;
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public AccountCategory getCategory() { return category; }
    public String getSubgroup() { return subgroup; }
    public NormalBalance getNormalBalance() { return normalBalance; }
    public Long getContraOfId() { return contraOfId; }
    public boolean isRequiresPartner() { return requiresPartner; }
    public StatementType getStatement() { return statement; }
    public boolean isSystem() { return system; }
    public boolean isActive() { return active; }
}
