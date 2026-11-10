package com.mzerp.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mzerp.support.IntegrationTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

@DisplayName("인증: 세션 로그인 + CSRF")
class AuthApiTest extends IntegrationTest {

    @Test
    @DisplayName("로그인하지 않으면 401")
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CSRF 토큰 발급 — XSRF-TOKEN 쿠키와 함께 내려온다 (SPA 방식)")
    void csrfEndpointIssuesCookie() throws Exception {
        mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }

    @Test
    @DisplayName("환경변수로 만든 첫 관리자로 로그인하면 /me가 된다")
    void bootstrapAdminCanLogin() throws Exception {
        MockHttpSession session = loginAsAdmin();
        mvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value(ADMIN_LOGIN));
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401 LOGIN_FAILED")
    void wrongPasswordIs401() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("loginId", ADMIN_LOGIN, "password", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    }

    @Test
    @DisplayName("CSRF 토큰 없이 상태를 바꾸는 요청은 403")
    void postWithoutCsrfIs403() throws Exception {
        MockHttpSession session = loginAsAdmin();
        mvc.perform(post("/api/v1/companies").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "CSRF 없는 회사")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("로그아웃하면 같은 세션으로 더는 접근할 수 없다")
    void logoutInvalidatesSession() throws Exception {
        MockHttpSession session = loginAsAdmin();
        mvc.perform(post("/api/v1/auth/logout").session(session).with(xsrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isUnauthorized());
    }
}
