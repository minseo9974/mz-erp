package com.mzerp.master;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mzerp.identity.AppUser;
import com.mzerp.identity.CompanyRole;
import com.mzerp.support.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

@DisplayName("기준정보: 회사·계정과목·거래처·회계연도")
class MasterDataApiTest extends IntegrationTest {

    private MockHttpSession admin;
    private Long companyId;

    @BeforeEach
    void setUp() throws Exception {
        admin = loginAsAdmin();
        companyId = createCompany(admin, "(주)엠제트전자");
    }

    private String base() {
        return "/api/v1/companies/" + companyId;
    }

    @Nested
    @DisplayName("회사")
    class CompanyTests {

        @Test
        @DisplayName("회사를 만든 사람은 그 회사의 ADMIN이 된다")
        void creatorBecomesAdmin() throws Exception {
            mvc.perform(get("/api/v1/me").session(admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.companies[?(@.companyId == " + companyId + ")].role").value("ADMIN"));
        }

        @Test
        @DisplayName("역할이 없는 회사에는 접근할 수 없다 (403)")
        void otherCompanyIsForbidden() throws Exception {
            AppUser outsider = createUser("outsider", "outsider-pass-1", null, null);
            MockHttpSession session = login(outsider.getLoginId(), "outsider-pass-1");
            mvc.perform(get(base() + "/accounts").session(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        }
    }

    @Nested
    @DisplayName("계정과목")
    class AccountTests {

        @Test
        @DisplayName("회사를 만들면 표준 계정과목표 94개가 복사된다")
        void standardChartIsCopied() throws Exception {
            mvc.perform(get(base() + "/accounts").session(admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(94)));
        }

        @Test
        @DisplayName("대손충당금(109)은 자산이지만 대변 잔액이고, 외상매출금(108)의 차감계정이다")
        void contraAccountIsLinked() throws Exception {
            mvc.perform(get(base() + "/accounts/109").session(admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.category").value("ASSET"))
                    .andExpect(jsonPath("$.normalBalance").value("CREDIT"))
                    .andExpect(jsonPath("$.contraOfCode").value("108"));
        }

        @Test
        @DisplayName("외상매출금(108)은 거래처 필수 계정이다")
        void receivableRequiresPartner() throws Exception {
            mvc.perform(get(base() + "/accounts/108").session(admin))
                    .andExpect(jsonPath("$.requiresPartner").value(true));
        }

        @Test
        @DisplayName("코드 앞자리나 이름으로 검색한다 (F2 코드 도움)")
        void searchByCodeOrName() throws Exception {
            mvc.perform(get(base() + "/accounts").param("q", "외상").session(admin))
                    .andExpect(jsonPath("$", hasSize(2))) // 108 외상매출금, 251 외상매입금
                    .andExpect(jsonPath("$[0].code").value("108"));
            mvc.perform(get(base() + "/accounts").param("q", "25").session(admin))
                    .andExpect(jsonPath("$[0].code").value("251"));
        }

        @Test
        @DisplayName("시스템 계정(400 손익)은 수정할 수 없다")
        void systemAccountIsLocked() throws Exception {
            mvc.perform(patch(base() + "/accounts/400").session(admin).with(xsrf())
                            .contentType(MediaType.APPLICATION_JSON).content(json("name", "바꾼 이름")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_SYSTEM_LOCKED"));
        }

        @Test
        @DisplayName("이미 있는 계정코드로 만들면 409 DUPLICATE_CODE")
        void duplicateAccountCode() throws Exception {
            mvc.perform(post(base() + "/accounts").session(admin).with(xsrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("code", "101", "name", "현금2", "category", "ASSET", "subgroup", "당좌자산",
                                    "normalBalance", "DEBIT", "statement", "BS")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        }

        @Test
        @DisplayName("차감계정은 대상과 정상잔액 방향이 반대여야 한다")
        void contraMustHaveOppositeBalance() throws Exception {
            mvc.perform(post(base() + "/accounts").session(admin).with(xsrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("code", "214", "name", "잘못된 차감", "category", "ASSET", "subgroup", "유형자산",
                                    "normalBalance", "DEBIT", "contraOfCode", "212", "statement", "BS")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_CONTRA_INVALID"));
        }

        @Test
        @DisplayName("입력값 검증 실패는 400 VALIDATION_FAILED와 필드 목록")
        void validationErrors() throws Exception {
            mvc.perform(post(base() + "/accounts").session(admin).with(xsrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("code", "ABC", "name", "")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[?(@.field == 'code')]").exists());
        }

        @Test
        @DisplayName("JSON 형식이 틀리거나 없는 enum 값이면 400 VALIDATION_FAILED")
        void unreadableBody() throws Exception {
            mvc.perform(post(base() + "/accounts").session(admin).with(xsrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("code", "199", "name", "임시", "category", "SOMETHING", "subgroup", "당좌자산",
                                    "normalBalance", "DEBIT", "statement", "BS")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("WRITER는 계정을 조회할 수 있지만 만들 수는 없다")
        void writerCannotCreateAccount() throws Exception {
            AppUser writer = createUser("writer", "writer-pass-1", companyId, CompanyRole.WRITER);
            MockHttpSession session = login(writer.getLoginId(), "writer-pass-1");
            mvc.perform(get(base() + "/accounts/101").session(session)).andExpect(status().isOk());
            mvc.perform(post(base() + "/accounts").session(session).with(xsrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("code", "199", "name", "임시", "category", "ASSET", "subgroup", "당좌자산",
                                    "normalBalance", "DEBIT", "statement", "BS")))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("거래처")
    class PartnerTests {

        @Test
        @DisplayName("거래처 등록 → 이름·사업자번호로 검색")
        void createAndSearch() throws Exception {
            mvc.perform(post(base() + "/partners").session(admin).with(xsrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("code", "00101", "name", "(주)한빛전자", "bizRegNo", "000-81-00101",
                                    "partnerType", "CUSTOMER")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("00101"));
            mvc.perform(get(base() + "/partners").param("q", "한빛").session(admin))
                    .andExpect(jsonPath("$", hasSize(1)));
            mvc.perform(get(base() + "/partners").param("q", "0008100101").session(admin))
                    .andExpect(jsonPath("$[0].name").value("(주)한빛전자"));
        }

        @Test
        @DisplayName("같은 회사에서 거래처코드가 겹치면 409, 다른 회사는 같은 코드를 써도 된다")
        void codeIsUniquePerCompany() throws Exception {
            String body = json("code", "00201", "name", "(주)대성소재", "partnerType", "VENDOR");
            mvc.perform(post(base() + "/partners").session(admin).with(xsrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
            mvc.perform(post(base() + "/partners").session(admin).with(xsrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));

            Long otherCompany = createCompany(admin, "다른 회사");
            mvc.perform(post("/api/v1/companies/" + otherCompany + "/partners").session(admin).with(xsrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("회계연도")
    class FiscalYearTests {

        @Test
        @DisplayName("제6기 2026년을 만들면 1~12월 회계기간이 함께 생긴다")
        void createWithTwelvePeriods() throws Exception {
            mvc.perform(post(base() + "/fiscal-years").session(admin).with(xsrf())
                            .contentType(MediaType.APPLICATION_JSON).content(json("year", 2026, "termNo", 6)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.termNo").value(6))
                    .andExpect(jsonPath("$.startDate").value("2026-01-01"))
                    .andExpect(jsonPath("$.endDate").value("2026-12-31"))
                    .andExpect(jsonPath("$.periods", hasSize(12)))
                    .andExpect(jsonPath("$.periods[1].endDate").value("2026-02-28"))
                    .andExpect(jsonPath("$.periods[11].startDate").value("2026-12-01"));
        }

        @Test
        @DisplayName("기수를 비우면 직전 기수 + 1")
        void termNoContinues() throws Exception {
            mvc.perform(post(base() + "/fiscal-years").session(admin).with(xsrf())
                    .contentType(MediaType.APPLICATION_JSON).content(json("year", 2026, "termNo", 6)));
            mvc.perform(post(base() + "/fiscal-years").session(admin).with(xsrf())
                            .contentType(MediaType.APPLICATION_JSON).content(json("year", 2027)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.termNo").value(7));
        }

        @Test
        @DisplayName("기간이 겹치는 회계연도는 409 FISCAL_YEAR_OVERLAP")
        void overlapIsRejected() throws Exception {
            mvc.perform(post(base() + "/fiscal-years").session(admin).with(xsrf())
                    .contentType(MediaType.APPLICATION_JSON).content(json("year", 2026)));
            // 2026-04-01 ~ 2027-03-31 은 2026년과 겹친다
            mvc.perform(post(base() + "/fiscal-years").session(admin).with(xsrf())
                            .contentType(MediaType.APPLICATION_JSON).content(json("year", 2026, "startMonth", 4)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("FISCAL_YEAR_OVERLAP"));
        }
    }
}
