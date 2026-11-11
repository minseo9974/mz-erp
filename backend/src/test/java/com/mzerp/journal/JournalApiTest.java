package com.mzerp.journal;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mzerp.identity.AppUser;
import com.mzerp.identity.CompanyRole;
import com.mzerp.support.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 일반전표 API — 시나리오 회사 (주)엠제트전자의 실제 거래로 검증한다.
 * 회계 노트 02, 해설 docs/walkthrough/02-journal-engine.md
 */
@DisplayName("일반전표 API: 작성 · 전기 · 역분개")
class JournalApiTest extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    private MockHttpSession admin;
    private Long companyId;

    @BeforeEach
    void setUp() throws Exception {
        admin = loginAsAdmin();
        companyId = createCompany(admin, "(주)엠제트전자");
        createFiscalYear(admin, companyId, 2026, 6);
        createPartner(admin, companyId, "00101", "(주)한빛전자", "CUSTOMER");
        createPartner(admin, companyId, "00201", "(주)대성소재", "VENDOR");
    }

    // ------------------------------------------------------------------ 요청 도우미

    private String base() {
        return "/api/v1/companies/" + companyId + "/journal-entries";
    }

    private static String line(String side, String account, String partner, long amount) {
        return partner == null
                ? "{\"side\":\"%s\",\"accountCode\":\"%s\",\"amount\":%d}".formatted(side, account, amount)
                : "{\"side\":\"%s\",\"accountCode\":\"%s\",\"partnerCode\":\"%s\",\"amount\":%d}".formatted(side, account, partner, amount);
    }

    private static String entry(String date, String description, String... lines) {
        return "{\"entryDate\":\"%s\",\"description\":\"%s\",\"lines\":[%s]}".formatted(date, description, String.join(",", lines));
    }

    /** 시나리오 #6: (주)한빛전자 받을어음 만기 → 보통예금 */
    private static String noteMaturity(String date) {
        return entry(date, "받을어음 만기", line("DEBIT", "103", null, 5_000_000), line("CREDIT", "110", "00101", 5_000_000));
    }

    private ResultActions create(MockHttpSession session, String body) throws Exception {
        return mvc.perform(post(base()).session(session).with(xsrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Long createId(String body) throws Exception {
        String json = create(admin, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private ResultActions postEntry(Long id) throws Exception {
        return mvc.perform(post(base() + "/" + id + "/post").session(admin).with(xsrf()));
    }

    private ResultActions reverse(Long id, String body) throws Exception {
        return mvc.perform(post(base() + "/" + id + "/reverse").session(admin).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ------------------------------------------------------------------ 작성

    @Nested
    @DisplayName("작성")
    class Create {

        @Test
        @DisplayName("임시 전표가 만들어지고, 같은 날 두 번째 전표는 2번")
        void createsDraftWithDailyNumber() throws Exception {
            create(admin, noteMaturity("2026-03-10"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.entryType").value("GENERAL"))
                    .andExpect(jsonPath("$.entryNo").value(1))
                    .andExpect(jsonPath("$.debitTotal").value(5_000_000))
                    .andExpect(jsonPath("$.creditTotal").value(5_000_000))
                    .andExpect(jsonPath("$.lines[0].accountName").value("보통예금"))
                    .andExpect(jsonPath("$.lines[1].side").value("CREDIT"))
                    .andExpect(jsonPath("$.lines[1].partnerName").value("(주)한빛전자"));

            create(admin, noteMaturity("2026-03-10")).andExpect(jsonPath("$.entryNo").value(2));
            create(admin, noteMaturity("2026-03-11")).andExpect(jsonPath("$.entryNo").value(1)); // 날짜가 바뀌면 1번부터
        }

        @Test
        @DisplayName("차대 불일치 → 422 JOURNAL_UNBALANCED (차액을 알려준다)")
        void unbalanced() throws Exception {
            create(admin, entry("2026-03-10", "x", line("DEBIT", "103", null, 5_000_000), line("CREDIT", "110", "00101", 4_000_000)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("JOURNAL_UNBALANCED"))
                    .andExpect(jsonPath("$.detail").value("차변 5,000,000원 / 대변 4,000,000원 (차액 1,000,000원)"));
        }

        @Test
        @DisplayName("거래처 필수 계정(110 받을어음)에 거래처가 없으면 → 422 PARTNER_REQUIRED")
        void partnerRequired() throws Exception {
            create(admin, entry("2026-03-10", "x", line("DEBIT", "103", null, 1_000), line("CREDIT", "110", null, 1_000)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("PARTNER_REQUIRED"));
        }

        @Test
        @DisplayName("결산 전용 시스템 계정(400 손익)은 쓸 수 없다 → 422 ACCOUNT_NOT_POSTABLE")
        void systemAccount() throws Exception {
            create(admin, entry("2026-03-10", "x", line("DEBIT", "400", null, 1_000), line("CREDIT", "101", null, 1_000)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_POSTABLE"));
        }

        @Test
        @DisplayName("회계연도가 없는 날짜 → 422 FISCAL_PERIOD_NOT_FOUND")
        void noFiscalYear() throws Exception {
            create(admin, noteMaturity("2025-12-31"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("FISCAL_PERIOD_NOT_FOUND"));
        }

        @Test
        @DisplayName("없는 계정·거래처 코드 → 404")
        void unknownCodes() throws Exception {
            create(admin, entry("2026-03-10", "x", line("DEBIT", "999", null, 1_000), line("CREDIT", "101", null, 1_000)))
                    .andExpect(status().isNotFound());
            create(admin, entry("2026-03-10", "x", line("DEBIT", "103", null, 1_000), line("CREDIT", "110", "99999", 1_000)))
                    .andExpect(status().isNotFound());
        }
    }

    // ------------------------------------------------------------------ 임시 전표 수정·삭제

    @Nested
    @DisplayName("임시 전표 수정·삭제")
    class EditDraft {

        @Test
        @DisplayName("날짜를 바꾸면 새 날짜의 번호를 받는다")
        void changeDateRenumbers() throws Exception {
            create(admin, noteMaturity("2026-03-12"));
            Long id = createId(noteMaturity("2026-03-10"));

            String body = "{\"version\":0," + noteMaturity("2026-03-12").substring(1);
            mvc.perform(put(base() + "/" + id).session(admin).with(xsrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entryDate").value("2026-03-12"))
                    .andExpect(jsonPath("$.entryNo").value(2))
                    .andExpect(jsonPath("$.version").value(1));
        }

        @Test
        @DisplayName("다른 사람이 먼저 고쳤으면(version 불일치) → 409 VERSION_CONFLICT")
        void versionConflict() throws Exception {
            Long id = createId(noteMaturity("2026-03-10"));
            String body = "{\"version\":5," + noteMaturity("2026-03-10").substring(1);
            mvc.perform(put(base() + "/" + id).session(admin).with(xsrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        }

        @Test
        @DisplayName("임시 전표는 삭제할 수 있다")
        void deleteDraft() throws Exception {
            Long id = createId(noteMaturity("2026-03-10"));
            mvc.perform(delete(base() + "/" + id).session(admin).with(xsrf())).andExpect(status().isNoContent());
            mvc.perform(get(base() + "/" + id).session(admin)).andExpect(status().isNotFound());
        }
    }

    // ------------------------------------------------------------------ 전기

    @Nested
    @DisplayName("전기")
    class Post {

        @Test
        @DisplayName("전기하면 POSTED, 이후 수정·삭제는 409 ENTRY_IMMUTABLE")
        void postedIsImmutable() throws Exception {
            Long id = createId(noteMaturity("2026-03-10"));
            postEntry(id)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("POSTED"))
                    .andExpect(jsonPath("$.postedAt").exists());

            String body = "{\"version\":1," + noteMaturity("2026-03-10").substring(1);
            mvc.perform(put(base() + "/" + id).session(admin).with(xsrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ENTRY_IMMUTABLE"));
            mvc.perform(delete(base() + "/" + id).session(admin).with(xsrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ENTRY_IMMUTABLE"));
        }

        @Test
        @DisplayName("작성자(WRITER)는 전표를 쓸 수 있지만 전기는 못 한다 → 403")
        void writerCannotPost() throws Exception {
            AppUser writer = createUser("writer", "writer-pass-1", companyId, CompanyRole.WRITER);
            MockHttpSession session = login(writer.getLoginId(), "writer-pass-1");
            String json = create(session, noteMaturity("2026-03-10")).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            Number id = JsonPath.read(json, "$.id");
            mvc.perform(post(base() + "/" + id + "/post").session(session).with(xsrf()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("일괄 전기는 하나라도 실패하면 전부 취소된다")
        void postAllIsAtomic() throws Exception {
            Long draft = createId(noteMaturity("2026-03-10"));
            Long posted = createId(noteMaturity("2026-03-10"));
            postEntry(posted).andExpect(status().isOk());

            mvc.perform(post(base() + "/post").session(admin).with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ids\":[" + draft + "," + posted + "]}"))
                    .andExpect(status().isConflict());
            mvc.perform(get(base() + "/" + draft).session(admin)).andExpect(jsonPath("$.status").value("DRAFT"));
        }

        @Test
        @DisplayName("마감된 달에는 전표를 만들 수도, 미리 만든 임시 전표를 전기할 수도 없다 → 409 PERIOD_CLOSED")
        void closedPeriod() throws Exception {
            Long draft = createId(noteMaturity("2026-03-10"));
            closePeriod(3); // 마감 기능은 5단계 — 지금은 DB에서 직접 닫는다

            create(admin, noteMaturity("2026-03-20"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
            postEntry(draft)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
        }
    }

    // ------------------------------------------------------------------ 역분개

    @Nested
    @DisplayName("역분개")
    class Reverse {

        /** 회계 노트 02 §5의 예: 출장비를 통신비(814)로 잘못 전기 */
        private Long postedWrongEntry() throws Exception {
            Long id = createId(entry("2026-12-05", "영업사원 출장비",
                    line("DEBIT", "814", null, 450_000), line("CREDIT", "101", null, 450_000)));
            postEntry(id).andExpect(status().isOk());
            return id;
        }

        @Test
        @DisplayName("차대를 뒤집은 전표가 바로 전기되고, 원전표와 서로 연결된다")
        void reversesAndLinks() throws Exception {
            Long original = postedWrongEntry();

            String json = reverse(original, "{\"reason\":\"계정 오류: 통신비 → 여비교통비\"}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.entryType").value("REVERSAL"))
                    .andExpect(jsonPath("$.status").value("POSTED"))
                    .andExpect(jsonPath("$.entryDate").value("2026-12-05"))
                    .andExpect(jsonPath("$.reversalOfId").value(original))
                    .andExpect(jsonPath("$.reversalReason").value("계정 오류: 통신비 → 여비교통비"))
                    .andExpect(jsonPath("$.lines[0].accountCode").value("814"))
                    .andExpect(jsonPath("$.lines[0].side").value("CREDIT"))
                    .andExpect(jsonPath("$.lines[1].accountCode").value("101"))
                    .andExpect(jsonPath("$.lines[1].side").value("DEBIT"))
                    .andReturn().getResponse().getContentAsString();
            Number reversalId = JsonPath.read(json, "$.id");

            mvc.perform(get(base() + "/" + original).session(admin))
                    .andExpect(jsonPath("$.reversedById").value(reversalId.longValue()));
        }

        @Test
        @DisplayName("같은 전표를 두 번 역분개할 수 없고, 역분개 전표 자체도 역분개할 수 없다 → 409")
        void onlyOnce() throws Exception {
            Long original = postedWrongEntry();
            String json = reverse(original, "{}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            Number reversalId = JsonPath.read(json, "$.id");

            reverse(original, "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ENTRY_ALREADY_REVERSED"));
            reverse(reversalId.longValue(), "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ENTRY_ALREADY_REVERSED"));
        }

        @Test
        @DisplayName("임시 전표는 역분개 대상이 아니다 → 409 ENTRY_NOT_POSTED")
        void draftIsNotReversed() throws Exception {
            Long draft = createId(noteMaturity("2026-03-10"));
            reverse(draft, "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ENTRY_NOT_POSTED"));
        }

        @Test
        @DisplayName("원전표의 달이 마감됐으면 정정 날짜를 지정해 역분개한다")
        void reverseIntoOpenPeriod() throws Exception {
            Long original = createId(noteMaturity("2026-03-10"));
            postEntry(original);
            closePeriod(3);

            reverse(original, "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
            reverse(original, "{\"reversalDate\":\"2026-04-01\",\"reason\":\"3월 마감 후 발견\"}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.entryDate").value("2026-04-01"));
        }

        @Test
        @DisplayName("역분개일이 원전표일보다 빠르면 422 REVERSAL_DATE_INVALID — 취소가 원거래보다 먼저 나올 수 없다")
        void reversalCannotPrecedeOriginal() throws Exception {
            Long original = postedWrongEntry(); // 12월 5일

            reverse(original, "{\"reversalDate\":\"2026-12-04\"}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("REVERSAL_DATE_INVALID"));
            reverse(original, "{\"reversalDate\":\"2026-12-05\"}").andExpect(status().isCreated()); // 같은 날은 된다
        }
    }

    // ------------------------------------------------------------------ 조회

    @Test
    @DisplayName("기간·상태로 조회하면 날짜·번호 순으로 나온다")
    void listByPeriodAndStatus() throws Exception {
        Long a = createId(noteMaturity("2026-03-11"));
        createId(noteMaturity("2026-03-10"));
        createId(noteMaturity("2026-04-01"));
        postEntry(a);

        mvc.perform(get(base()).param("from", "2026-03-01").param("to", "2026-03-31").session(admin))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].entryDate").value("2026-03-10"))
                .andExpect(jsonPath("$[1].entryDate").value("2026-03-11"));
        mvc.perform(get(base()).param("from", "2026-03-01").param("to", "2026-12-31").param("status", "POSTED").session(admin))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(a));
    }

    private void closePeriod(int periodNo) {
        jdbc.sql("""
                        update fiscal_period set status = 'CLOSED', closed_at = now()
                         where period_no = :no
                           and fiscal_year_id in (select id from fiscal_year where company_id = :companyId)
                        """)
                .param("no", periodNo)
                .param("companyId", companyId)
                .update();
    }
}
