package com.mzerp.journal;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mzerp.support.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 전기분 재무상태표 API — 기초잔액은 회계연도 첫날의 OPENING 전표 한 장이다 (회계 노트 03, ADR-0004).
 */
@DisplayName("전기분 재무상태표: 기초잔액 전표 저장 · 교체 · 전기 후 정정")
class OpeningBalanceApiTest extends IntegrationTest {

    private MockHttpSession admin;
    private Long companyId;

    @BeforeEach
    void setUp() throws Exception {
        admin = loginAsAdmin();
        companyId = createCompany(admin, "(주)엠제트전자");
        createFiscalYear(admin, companyId, 2026, 6);
        createPartner(admin, companyId, "00101", "(주)한빛전자", "CUSTOMER");
    }

    private String url(int year) {
        return "/api/v1/companies/" + companyId + "/fiscal-years/" + year + "/opening-balance";
    }

    private String entries() {
        return "/api/v1/companies/" + companyId + "/journal-entries";
    }

    private static String line(String side, String account, String partner, long amount) {
        return partner == null
                ? "{\"side\":\"%s\",\"accountCode\":\"%s\",\"amount\":%d}".formatted(side, account, amount)
                : "{\"side\":\"%s\",\"accountCode\":\"%s\",\"partnerCode\":\"%s\",\"amount\":%d}".formatted(side, account, partner, amount);
    }

    private ResultActions save(int year, String... lines) throws Exception {
        return mvc.perform(put(url(year)).session(admin).with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[" + String.join(",", lines) + "]}"));
    }

    /** 현금 5,000,000 · 외상매출금 12,000,000(한빛전자) · 대손충당금 120,000 / 자본금 16,880,000 */
    private ResultActions saveSmallBalanceSheet() throws Exception {
        return save(2026,
                line("DEBIT", "101", null, 5_000_000),
                line("DEBIT", "108", "00101", 12_000_000),
                line("CREDIT", "109", null, 120_000),
                line("CREDIT", "331", null, 16_880_000));
    }

    private Long entryId(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.entry.id")).longValue();
    }

    @Test
    @DisplayName("저장하면 회계연도 첫날의 임시 OPENING 전표가 생기고, 조회하면 그대로 나온다")
    void savesAsDraftOpeningEntry() throws Exception {
        mvc.perform(get(url(2026)).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termNo").value(6))
                .andExpect(jsonPath("$.entry").value(nullValue()));

        saveSmallBalanceSheet()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entry.entryType").value("OPENING"))
                .andExpect(jsonPath("$.entry.status").value("DRAFT"))
                .andExpect(jsonPath("$.entry.entryDate").value("2026-01-01"))
                .andExpect(jsonPath("$.entry.debitTotal").value(17_000_000))
                .andExpect(jsonPath("$.entry.creditTotal").value(17_000_000))
                .andExpect(jsonPath("$.entry.lines[1].partnerName").value("(주)한빛전자"));

        mvc.perform(get(url(2026)).session(admin))
                .andExpect(jsonPath("$.entry.lines", hasSize(4)));
    }

    @Test
    @DisplayName("임시 상태에서 다시 저장하면 같은 전표의 라인이 통째로 바뀐다")
    void savingAgainReplacesTheDraft() throws Exception {
        Long first = entryId(saveSmallBalanceSheet());

        save(2026, line("DEBIT", "103", null, 30_000_000), line("CREDIT", "331", null, 30_000_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entry.id").value(first))
                .andExpect(jsonPath("$.entry.entryNo").value(1))
                .andExpect(jsonPath("$.entry.lines", hasSize(2)))
                .andExpect(jsonPath("$.entry.debitTotal").value(30_000_000));
    }

    @Test
    @DisplayName("수익·비용 계정은 넣을 수 없다 → 422 ACCOUNT_NOT_POSTABLE")
    void incomeStatementAccountsAreRejected() throws Exception {
        save(2026, line("DEBIT", "101", null, 1_000_000), line("CREDIT", "404", null, 1_000_000))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_POSTABLE"));
    }

    @Test
    @DisplayName("자산 ≠ 부채 + 자본이면 422, 거래처가 필요한 계정에 거래처가 없어도 422")
    void commonEntryRulesStillApply() throws Exception {
        save(2026, line("DEBIT", "101", null, 5_000_000), line("CREDIT", "331", null, 4_000_000))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("JOURNAL_UNBALANCED"));
        save(2026, line("DEBIT", "108", null, 5_000_000), line("CREDIT", "331", null, 5_000_000))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PARTNER_REQUIRED"));
    }

    @Test
    @DisplayName("전기한 뒤에는 저장할 수 없고(409), 역분개하면 다시 입력할 수 있다")
    void postedOpeningIsCorrectedByReversal() throws Exception {
        Long first = entryId(saveSmallBalanceSheet());
        mvc.perform(post(entries() + "/" + first + "/post").session(admin).with(xsrf())).andExpect(status().isOk());

        saveSmallBalanceSheet()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_IMMUTABLE"));

        mvc.perform(post(entries() + "/" + first + "/reverse").session(admin).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"기초 현금 금액 오류\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get(url(2026)).session(admin))
                .andExpect(jsonPath("$.entry").value(nullValue())); // 역분개된 전표는 살아 있는 기초잔액이 아니다

        Long second = entryId(saveSmallBalanceSheet().andExpect(status().isOk())
                .andExpect(jsonPath("$.entry.status").value("DRAFT"))
                .andExpect(jsonPath("$.entry.entryNo").value(3))); // 1 기초잔액, 2 역분개, 3 새 기초잔액
        org.assertj.core.api.Assertions.assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("둘째 회계연도에는 직접 입력할 수 없다 → 422, 없는 연도는 404")
    void onlyFirstFiscalYear() throws Exception {
        createFiscalYear(admin, companyId, 2027, 7);

        save(2027, line("DEBIT", "101", null, 1_000), line("CREDIT", "331", null, 1_000))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPENING_NOT_FIRST_YEAR"));
        mvc.perform(get(url(2030)).session(admin)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("기초잔액 전표는 일반전표 수정 API로 고칠 수 없다 — 재무상태표 계정 규칙을 건너뛰지 못하게")
    void cannotBeEditedThroughGeneralJournalApi() throws Exception {
        Long id = entryId(saveSmallBalanceSheet());

        mvc.perform(put(entries() + "/" + id).session(admin).with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"entryDate\":\"2026-01-01\",\"lines\":["
                                + line("DEBIT", "812", null, 1_000) + "," + line("CREDIT", "101", null, 1_000) + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
