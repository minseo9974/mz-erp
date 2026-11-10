package com.mzerp.master.partner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "partner")
public class Partner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "biz_reg_no")
    private String bizRegNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "partner_type", nullable = false)
    private PartnerType partnerType;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Partner() {
    }

    public Partner(Long companyId, String code, String name, String bizRegNo, PartnerType partnerType) {
        this.companyId = companyId;
        this.code = code;
        this.name = name;
        this.bizRegNo = bizRegNo;
        this.partnerType = partnerType;
    }

    public void update(String name, String bizRegNo, PartnerType partnerType, Boolean active) {
        if (name != null) this.name = name;
        if (bizRegNo != null) this.bizRegNo = bizRegNo;
        if (partnerType != null) this.partnerType = partnerType;
        if (active != null) this.active = active;
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getBizRegNo() { return bizRegNo; }
    public PartnerType getPartnerType() { return partnerType; }
    public boolean isActive() { return active; }
}
