package com.mzerp.identity;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 회사 단위 권한 검사. 컨트롤러에서 이렇게 쓴다:
 * <pre>@PreAuthorize("@companyAccess.has(#companyId, 'WRITER')")</pre>
 * 역할이 없거나 부족하면 403(FORBIDDEN_ROLE). 남의 회사 id를 넣어도 같은 403이라 회사 존재 여부가 드러나지 않는다.
 */
@Component("companyAccess")
public class CompanyAccess {

    private final UserCompanyRoleRepository roles;

    public CompanyAccess(UserCompanyRoleRepository roles) {
        this.roles = roles;
    }

    public boolean has(Long companyId, String requiredRole) {
        CompanyRole required = CompanyRole.valueOf(requiredRole);
        return currentUserId()
                .flatMap(userId -> roles.findByUserIdAndCompanyId(userId, companyId))
                .map(r -> r.getRole().includes(required))
                .orElse(false);
    }

    public static Optional<Long> currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return Optional.of(p.userId());
        }
        return Optional.empty();
    }
}
