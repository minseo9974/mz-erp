package com.mzerp.identity;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCompanyRoleRepository extends JpaRepository<UserCompanyRole, Long> {

    Optional<UserCompanyRole> findByUserIdAndCompanyId(Long userId, Long companyId);

    List<UserCompanyRole> findByUserIdOrderByCompanyId(Long userId);
}
