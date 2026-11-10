package com.mzerp.master.partner;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerRepository extends JpaRepository<Partner, Long> {

    List<Partner> findByCompanyIdOrderByCode(Long companyId);

    Optional<Partner> findByCompanyIdAndCode(Long companyId, String code);

    boolean existsByCompanyIdAndCode(Long companyId, String code);
}
