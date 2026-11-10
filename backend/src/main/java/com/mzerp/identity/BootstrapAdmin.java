package com.mzerp.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자가 한 명도 없을 때, 환경변수(MZERP_ADMIN_LOGIN_ID / MZERP_ADMIN_PASSWORD)로 첫 사용자를 만든다.
 * 비밀번호를 코드나 설정 파일에 두지 않기 위한 장치. 이 사용자가 회사를 만들면 그 회사의 ADMIN이 된다.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String loginId;
    private final String password;
    private final String name;

    public BootstrapAdmin(AppUserRepository users, PasswordEncoder passwordEncoder,
                          @Value("${mzerp.bootstrap.admin-login-id:}") String loginId,
                          @Value("${mzerp.bootstrap.admin-password:}") String password,
                          @Value("${mzerp.bootstrap.admin-name:관리자}") String name) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.loginId = loginId;
        this.password = password;
        this.name = name;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (loginId.isBlank() || password.isBlank() || users.count() > 0) {
            return;
        }
        users.save(new AppUser(loginId, passwordEncoder.encode(password), name));
        log.info("첫 관리자 사용자 '{}'를 만들었습니다", loginId);
    }
}
