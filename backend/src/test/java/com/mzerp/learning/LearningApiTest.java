package com.mzerp.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mzerp.identity.AppUser;
import com.mzerp.support.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 실습 한 바퀴: 과정 시작 → 미션 시작 → ERP에서 전표 작성 → 결재 요청(반려·승인) → 힌트 · 정답 대신 처리.
 * 실습자는 일반전표 API(실제 ERP)를 쓰고, 실습 API는 채점과 진행만 맡는다 (ADR-0012, 0013, 0014).
 */
@DisplayName("실습: 과정 시작부터 결재 승인까지")
class LearningApiTest extends IntegrationTest {

    private static final String PASSWORD = "learner-pass-1";

    @Autowired
    private JdbcClient jdbc;

    private MockHttpSession learner;
    private Long enrollmentId;
    private Long companyId;

    @BeforeEach
    void setUp() throws Exception {
        AppUser user = createUser("learner", PASSWORD, null, null);
        learner = login(user.getLoginId(), PASSWORD);
        String json = enroll(learner).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        enrollmentId = ((Number) JsonPath.read(json, "$.id")).longValue();
        companyId = ((Number) JsonPath.read(json, "$.companyId")).longValue();
    }

    // ------------------------------------------------------------------ 요청 도우미

    private ResultActions enroll(MockHttpSession session) throws Exception {
        return mvc.perform(post("/api/v1/learning/enrollments").session(session).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content(json("courseKey", "accounting-basics")));
    }

    private Long start(String missionKey) throws Exception {
        String json = mvc.perform(post("/api/v1/learning/enrollments/" + enrollmentId + "/missions/" + missionKey + "/start")
                        .session(learner).with(xsrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    /** 앞 미션들을 "정답 보고 넘어가기"로 끝내 두고 그 미션을 시작한다 (미션은 과정 순서대로만 열린다). */
    private Long startAfterSkipping(String missionKey, String... before) throws Exception {
        for (String key : before) {
            attemptAction(start(key), "reveal").andExpect(status().isOk());
        }
        return start(missionKey);
    }

    private ResultActions attemptAction(Long attemptId, String action) throws Exception {
        return mvc.perform(post("/api/v1/learning/attempts/" + attemptId + "/" + action).session(learner).with(xsrf()));
    }

    private String entries() {
        return "/api/v1/companies/" + companyId + "/journal-entries";
    }

    private static String entryBody(String date, String... lines) {
        return "{\"entryDate\":\"%s\",\"lines\":[%s]}".formatted(date, String.join(",", lines));
    }

    private static String line(String side, String account, String partner, long amount) {
        return partner == null
                ? "{\"side\":\"%s\",\"accountCode\":\"%s\",\"amount\":%d}".formatted(side, account, amount)
                : "{\"side\":\"%s\",\"accountCode\":\"%s\",\"partnerCode\":\"%s\",\"amount\":%d}".formatted(side, account, partner, amount);
    }

    private Long createDraft(String body) throws Exception {
        String json = mvc.perform(post(entries()).session(learner).with(xsrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private void updateDraft(Long id, int version, String body) throws Exception {
        mvc.perform(put(entries() + "/" + id).session(learner).with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + "," + body.substring(1)))
                .andExpect(status().isOk());
    }

    private long balance(String account, String partner) {
        return jdbc.sql("""
                        select coalesce(sum(l.debit_amount) - sum(l.credit_amount), 0)
                          from journal_line l
                          join journal_entry e on e.id = l.entry_id
                          join account a on a.id = l.account_id
                          left join partner p on p.id = l.partner_id
                         where e.company_id = ? and e.status = 'POSTED' and a.code = ? and p.code is not distinct from ?
                        """)
                .params(companyId, account, partner).query(Long.class).single();
    }

    // ------------------------------------------------------------------ 과정 시작

    @Test
    @DisplayName("과정을 시작하면 나만의 실습 회사가 생긴다: 기초잔액은 전기되어 있고, 나는 작성자(WRITER)다")
    void enrollingCreatesASandboxCompany() throws Exception {
        mvc.perform(get("/api/v1/learning/enrollments/" + enrollmentId).session(learner))
                .andExpect(jsonPath("$.courseTitle").value("회계팀 첫 1년"))
                .andExpect(jsonPath("$.chapters[0].missions", hasSize(6)))
                .andExpect(jsonPath("$.chapters[0].missions[0].status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.chapters[0].missions[0].date").value("2026-01-10"));
        mvc.perform(get("/api/v1/learning/enrollments").session(learner))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(enrollmentId));
        mvc.perform(get("/api/v1/me").session(learner))
                .andExpect(jsonPath("$.companies", hasSize(1)))
                .andExpect(jsonPath("$.companies[0].companyId").value(companyId))
                .andExpect(jsonPath("$.companies[0].role").value("WRITER"));

        assertThat(balance("103", null)).isEqualTo(30_000_000);        // 기초 보통예금
        assertThat(balance("108", "00101")).isEqualTo(12_000_000);     // 기초 외상매출금 — 한빛전자

        // 다시 시작해도 같은 수강 (회사를 또 만들지 않는다)
        enroll(learner).andExpect(jsonPath("$.id").value(enrollmentId)).andExpect(jsonPath("$.companyId").value(companyId));
    }

    @Test
    @DisplayName("남의 수강과 미션 시도는 보이지 않는다 → 404")
    void othersCannotSeeMyEnrollment() throws Exception {
        Long attemptId = start("withholding-tax-payment");
        AppUser other = createUser("other", PASSWORD, null, null);
        MockHttpSession otherSession = login(other.getLoginId(), PASSWORD);

        mvc.perform(get("/api/v1/learning/enrollments/" + enrollmentId).session(otherSession)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/learning/attempts/" + attemptId + "/submit").session(otherSession).with(xsrf()))
                .andExpect(status().isNotFound());
        mvc.perform(get(entries()).session(otherSession)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 미션: 결재 요청

    @Test
    @DisplayName("원천세 납부: 전표 없음 → 차대 반대 → 일자 오류로 세 번 반려되고, 고치면 팀장이 전기한다")
    void rejectedUntilCorrectThenPostedByTeamLead() throws Exception {
        Long attemptId = start("withholding-tax-payment");

        attemptAction(attemptId, "submit")
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.findings[0].type").value("NO_ENTRY"));

        // (차) 보통예금 / (대) 예수금 — 거꾸로
        Long entryId = createDraft(entryBody("2026-01-10", line("DEBIT", "103", null, 250_000), line("CREDIT", "254", null, 250_000)));
        attemptAction(attemptId, "submit")
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.findings[0].type").value("SIDE_SWAPPED"));

        // 분개는 맞지만 전표 일자가 거래일(1월 10일)이 아니다
        updateDraft(entryId, 0, entryBody("2026-01-12", line("DEBIT", "254", null, 250_000), line("CREDIT", "103", null, 250_000)));
        String rejected = attemptAction(attemptId, "submit")
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.findings[0].type").value("WRONG_DATE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(rejected).contains("거래가 일어난 날");
        mvc.perform(get(entries() + "/" + entryId).session(learner)).andExpect(jsonPath("$.status").value("DRAFT")); // 반려된 전표는 임시로 남는다

        Number version = JsonPath.read(mvc.perform(get(entries() + "/" + entryId).session(learner)).andReturn()
                .getResponse().getContentAsString(), "$.version");
        // 날짜를 바꾸면 그 날짜의 새 번호를 받는다
        updateDraft(entryId, version.intValue(), entryBody("2026-01-10", line("DEBIT", "254", null, 250_000), line("CREDIT", "103", null, 250_000)));
        attemptAction(attemptId, "submit")
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.findings", hasSize(0)))
                .andExpect(jsonPath("$.attempt.status").value("PASSED"))
                .andExpect(jsonPath("$.attempt.submissions").value(4));

        mvc.perform(get(entries() + "/" + entryId).session(learner)).andExpect(jsonPath("$.status").value("POSTED"));
        assertThat(jdbc.sql("select u.login_id from journal_entry e join app_user u on u.id = e.posted_by where e.id = ?")
                .param(entryId).query(String.class).single()).isEqualTo(SandboxFactory.TEAM_LEAD_LOGIN);
        assertThat(balance("254", null)).isZero(); // 예수금 250,000을 냈으니 0
        mvc.perform(get("/api/v1/learning/enrollments/" + enrollmentId).session(learner))
                .andExpect(jsonPath("$.chapters[0].missions[0].status").value("PASSED"));

        attemptAction(attemptId, "submit").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MISSION_ALREADY_FINISHED"));
    }

    @Test
    @DisplayName("실습자는 자기 전표를 직접 전기할 수 없다 (403) — 전기는 결재를 통과해야 일어난다")
    void learnerCannotPostDirectly() throws Exception {
        start("withholding-tax-payment");
        Long entryId = createDraft(entryBody("2026-01-10", line("DEBIT", "254", null, 250_000), line("CREDIT", "103", null, 250_000)));

        mvc.perform(post(entries() + "/" + entryId + "/post").session(learner).with(xsrf())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("거래처를 잘못 고르면 WRONG_PARTNER — 미지급금을 케이렌탈이 아닌 다른 거래처로")
    void wrongPartnerIsExplained() throws Exception {
        Long attemptId = startAfterSkipping("accounts-payable-other-payment", "withholding-tax-payment");
        createDraft(entryBody("2026-01-15", line("DEBIT", "253", "00603", 1_500_000), line("CREDIT", "103", null, 1_500_000)));

        attemptAction(attemptId, "submit")
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.findings[0].type").value("WRONG_PARTNER"));
    }

    // ------------------------------------------------------------------ 미션 진행

    @Test
    @DisplayName("미션은 한 번에 하나만: 진행 중에 다른 미션을 시작하면 409, 같은 미션을 다시 시작하면 그 시도를 돌려준다")
    void oneMissionAtATime() throws Exception {
        Long attemptId = start("withholding-tax-payment");

        mvc.perform(post("/api/v1/learning/enrollments/" + enrollmentId + "/missions/receivable-collection/start")
                        .session(learner).with(xsrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MISSION_IN_PROGRESS"));
        assertThat(start("withholding-tax-payment")).isEqualTo(attemptId);
        mvc.perform(get("/api/v1/learning/enrollments/" + enrollmentId).session(learner))
                .andExpect(jsonPath("$.chapters[0].missions[0].attemptId").value(attemptId));
    }

    @Test
    @DisplayName("미션은 과정 순서대로 열린다 — 앞 미션이 끝나지 않았으면 409 MISSION_LOCKED")
    void missionsUnlockInOrder() throws Exception {
        mvc.perform(post("/api/v1/learning/enrollments/" + enrollmentId + "/missions/accounts-payable-other-payment/start")
                        .session(learner).with(xsrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MISSION_LOCKED"))
                .andExpect(jsonPath("$.detail").value("'원천세 납부' 미션을 먼저 끝내세요"));
    }

    @Test
    @DisplayName("미션 밖에서 써 둔 임시 전표가 있으면 미션을 시작할 수 없다 — 이번 결재에 섞여 올라가지 않게")
    void leftoverDraftsBlockStart() throws Exception {
        Long stray = createDraft(entryBody("2026-01-05", line("DEBIT", "101", null, 10_000), line("CREDIT", "103", null, 10_000)));

        mvc.perform(post("/api/v1/learning/enrollments/" + enrollmentId + "/missions/withholding-tax-payment/start")
                        .session(learner).with(xsrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MISSION_DRAFTS_LEFT"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(entries() + "/" + stray)
                .session(learner).with(xsrf())).andExpect(status().isNoContent());
        start("withholding-tax-payment");
    }

    @Test
    @DisplayName("힌트는 요청할 때마다 하나씩 열리고, 다 열면 더 늘지 않는다")
    void hintsOpenOneByOne() throws Exception {
        Long attemptId = startAfterSkipping("receivable-collection", "withholding-tax-payment", "accounts-payable-other-payment");

        mvc.perform(get("/api/v1/learning/attempts/" + attemptId).session(learner))
                .andExpect(jsonPath("$.date").value("2026-02-25"))
                .andExpect(jsonPath("$.hints", hasSize(0)))
                .andExpect(jsonPath("$.hintsRemaining").value(2));
        attemptAction(attemptId, "hint").andExpect(jsonPath("$.hints", hasSize(1))).andExpect(jsonPath("$.hintsRemaining").value(1));
        attemptAction(attemptId, "hint").andExpect(jsonPath("$.hints", hasSize(2)));
        attemptAction(attemptId, "hint").andExpect(jsonPath("$.hints", hasSize(2))).andExpect(jsonPath("$.hintsRemaining").value(0));
    }

    @Test
    @DisplayName("정답 보고 넘어가기: 쓰던 임시 전표는 지워지고 시스템이 정답 전표를 전기한다")
    void revealReplacesMyDraftWithTheAnswer() throws Exception {
        Long attemptId = startAfterSkipping("receivable-collection", "withholding-tax-payment", "accounts-payable-other-payment");
        createDraft(entryBody("2026-02-25", line("DEBIT", "103", null, 12_000_000), line("CREDIT", "404", null, 12_000_000))); // 매출을 또 잡는 실수

        attemptAction(attemptId, "reveal").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVEALED"));

        assertThat(jdbc.sql("select count(*) from journal_entry where company_id = ? and status = 'DRAFT'")
                .param(companyId).query(Long.class).single()).isZero();
        assertThat(balance("108", "00101")).isZero();          // 한빛전자 외상매출금 12,000,000 회수됨
        // 기초 30,000,000 − 원천세 250,000 − 미지급금 1,500,000 (앞 미션 두 개) + 회수 12,000,000
        assertThat(balance("103", null)).isEqualTo(40_250_000);
        assertThat(balance("404", null)).isZero();              // 잘못 잡으려던 매출은 장부에 없다
        mvc.perform(get("/api/v1/learning/enrollments/" + enrollmentId).session(learner))
                .andExpect(jsonPath("$.chapters[0].missions[2].status").value("REVEALED"));
    }
}
