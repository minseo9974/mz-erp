package com.mzerp.master.partner;

import java.util.List;
import java.util.Locale;

import com.mzerp.common.error.ApiException;
import com.mzerp.common.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartnerService {

    private final PartnerRepository partners;

    public PartnerService(PartnerRepository partners) {
        this.partners = partners;
    }

    public record CreateCommand(String code, String name, String bizRegNo, PartnerType partnerType) {
    }

    public record UpdateCommand(String name, String bizRegNo, PartnerType partnerType, Boolean active) {
    }

    /**
     * 거래처 검색. 1단계는 회사당 거래처가 수백 건 이하라고 보고 메모리에서 거른다.
     * 거래처가 수천 건이 되면 DB 검색(이름 LIKE + 페이징)으로 바꾼다.
     */
    @Transactional(readOnly = true)
    public List<Partner> search(Long companyId, String q, PartnerType type, boolean activeOnly) {
        String keyword = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        return partners.findByCompanyIdOrderByCode(companyId).stream()
                .filter(p -> keyword.isEmpty() || p.getCode().startsWith(keyword)
                        || p.getName().toLowerCase(Locale.ROOT).contains(keyword)
                        || (p.getBizRegNo() != null && p.getBizRegNo().replace("-", "").contains(keyword.replace("-", ""))))
                .filter(p -> type == null || p.getPartnerType() == type)
                .filter(p -> !activeOnly || p.isActive())
                .toList();
    }

    @Transactional(readOnly = true)
    public Partner get(Long companyId, String code) {
        return partners.findByCompanyIdAndCode(companyId, code)
                .orElseThrow(() -> ApiException.notFound("거래처 " + code));
    }

    @Transactional
    public Partner create(Long companyId, CreateCommand c) {
        if (partners.existsByCompanyIdAndCode(companyId, c.code())) {
            throw new ApiException(ErrorCode.DUPLICATE_CODE, "거래처코드 " + c.code() + "은(는) 이미 있습니다");
        }
        return partners.save(new Partner(companyId, c.code(), c.name(), c.bizRegNo(), c.partnerType()));
    }

    @Transactional
    public Partner update(Long companyId, String code, UpdateCommand c) {
        Partner partner = get(companyId, code);
        partner.update(c.name(), c.bizRegNo(), c.partnerType(), c.active());
        return partner;
    }
}
