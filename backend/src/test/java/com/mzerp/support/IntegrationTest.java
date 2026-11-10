package com.mzerp.support;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

import com.mzerp.identity.AppUser;
import com.mzerp.identity.AppUserRepository;
import com.mzerp.identity.CompanyRole;
import com.mzerp.identity.UserCompanyRole;
import com.mzerp.identity.UserCompanyRoleRepository;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 통합 테스트 기반 클래스.
 * <ul>
 *   <li>실제 PostgreSQL 16을 프로세스로 띄운다 (Docker 불필요, ADR-0009). JVM당 1번만 시작하고 모든 테스트가 공유</li>
 *   <li>Flyway 마이그레이션이 그대로 적용되므로 스키마·제약 조건까지 운영과 같다</li>
 *   <li>테스트끼리 데이터를 지우지 않는 대신, 회사·사용자를 매번 새로 만들어 서로 간섭하지 않게 한다</li>
 * </ul>
 */
@SpringBootTest
public abstract class IntegrationTest {

    protected static final String ADMIN_LOGIN = "bootstrap-admin";
    protected static final String ADMIN_PASSWORD = "bootstrap-pass-1234";

    private static final EmbeddedPostgres POSTGRES = start();

    private static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException("임베디드 PostgreSQL을 시작하지 못했습니다", e);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("mzerp.bootstrap.admin-login-id", () -> ADMIN_LOGIN);
        registry.add("mzerp.bootstrap.admin-password", () -> ADMIN_PASSWORD);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    protected AppUserRepository users;

    @Autowired
    protected UserCompanyRoleRepository roles;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    protected MockMvc mvc;

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /**
     * 실제 SPA와 똑같이 CSRF 토큰을 붙인다: GET /api/v1/auth/csrf로 XSRF-TOKEN 쿠키를 받고,
     * 그 쿠키와 같은 값을 X-XSRF-TOKEN 헤더로 보낸다.
     * (spring-security-test의 csrf()는 공유 CsrfFilter의 저장소를 테스트용으로 바꿔 끼워서
     * 운영 설정과 다른 상태로 테스트하게 되므로 쓰지 않는다.)
     */
    protected RequestPostProcessor xsrf() throws Exception {
        Cookie cookie = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        if (cookie == null) {
            throw new IllegalStateException("XSRF-TOKEN 쿠키가 발급되지 않았습니다");
        }
        return request -> {
            request.setCookies(cookie);
            request.addHeader("X-XSRF-TOKEN", cookie.getValue());
            return request;
        };
    }

    /** 로그인하고 세션을 돌려준다. 이후 요청에 .session(session).with(xsrf())로 쓴다. */
    protected MockHttpSession login(String loginId, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("loginId", loginId, "password", password)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    protected MockHttpSession loginAsAdmin() throws Exception {
        return login(ADMIN_LOGIN, ADMIN_PASSWORD);
    }

    /** 새 사용자를 만들고 (선택) 회사 역할을 준다. 로그인 아이디는 테스트마다 겹치지 않게 접미사를 붙인다. */
    protected AppUser createUser(String prefix, String password, Long companyId, CompanyRole role) {
        AppUser user = users.save(new AppUser(prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                passwordEncoder.encode(password), prefix));
        if (companyId != null) {
            roles.save(new UserCompanyRole(user.getId(), companyId, role));
        }
        return user;
    }

    /** 회사를 만들고 id를 돌려준다 (만든 사용자 = ADMIN). */
    protected Long createCompany(MockHttpSession session, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/companies").session(session).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", name, "bizRegNo", "000-81-00001", "industry", "제조업")))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return Long.valueOf(body.replaceAll(".*\"companyId\":(\\d+).*", "$1"));
    }

    /** 간단한 JSON 객체 문자열. 값은 문자열·숫자·불리언만 (테스트 가독성용). */
    protected static String json(Object... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(keyValues[i]).append("\":");
            Object v = keyValues[i + 1];
            if (v instanceof String s) {
                sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.append('}').toString();
    }
}
