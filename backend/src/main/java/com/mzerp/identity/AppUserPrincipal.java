package com.mzerp.identity;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 세션에 저장되는 로그인 사용자. 회사별 역할은 요청마다 DB에서 확인하므로 여기에 넣지 않는다
 * (역할을 바꾸면 다음 요청부터 바로 반영되도록).
 */
public record AppUserPrincipal(Long userId, String loginId, String name, String passwordHash, boolean active)
        implements UserDetails, Serializable {

    static AppUserPrincipal from(AppUser user) {
        return new AppUserPrincipal(user.getId(), user.getLoginId(), user.getName(), user.getPasswordHash(), user.isActive());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return loginId;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
