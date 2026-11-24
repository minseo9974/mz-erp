package com.mzerp.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.TreeMap;

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
                .andExpect(jsonPath("$.chapters[0].missions[0].kind").value("ENTRY"))
                .andExpect(jsonPath("$.chapters[0].missions[0].status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.chapters[0].missions[0].date").value("2026-01-12"));
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
        Long entryId = createDraft(entryBody("2026-01-12", line("DEBIT", "103", null, 250_000), line("CREDIT", "254", null, 250_000)));
        attemptAction(attemptId, "submit")
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.findings[0].type").value("SIDE_SWAPPED"));

        // 분개는 맞지만 전표 일자가 거래일(1월 12일)이 아니다
        updateDraft(entryId, 0, entryBody("2026-01-13", line("DEBIT", "254", null, 250_000), line("CREDIT", "103", null, 250_000)));
        String rejected = attemptAction(attemptId, "submit")
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.findings[0].type").value("WRONG_DATE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(rejected).contains("거래가 일어난 날");
        mvc.perform(get(entries() + "/" + entryId).session(learner)).andExpect(jsonPath("$.status").value("DRAFT")); // 반려된 전표는 임시로 남는다

        Number version = JsonPath.read(mvc.perform(get(entries() + "/" + entryId).session(learner)).andReturn()
                .getResponse().getContentAsString(), "$.version");
        // 날짜를 바꾸면 그 날짜의 새 번호를 받는다
        updateDraft(entryId, version.intValue(), entryBody("2026-01-12", line("DEBIT", "254", null, 250_000), line("CREDIT", "103", null, 250_000)));
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
        Long entryId = createDraft(entryBody("2026-01-12", line("DEBIT", "254", null, 250_000), line("CREDIT", "103", null, 250_000)));

        mvc.perform(post(entries() + "/" + entryId + "/post").session(learner).with(xsrf())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("오답 예시에 걸리면 MISTAKE로 미션 전용 사유를 먼저 돌려주고, 아니면 범용 진단(조사·차액)을 쓴다")
    void mistakeFeedbackPrecedesGenericDiagnosis() throws Exception {
        Long attemptId = start("withholding-tax-payment");

        // 원천세 → 세금과공과(817): 1장 미션의 오답 예시
        Long entryId = createDraft(entryBody("2026-01-12", line("DEBIT", "817", null, 250_000), line("CREDIT", "103", null, 250_000)));
        attemptAction(attemptId, "submit")
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.findings[0].type").value("MISTAKE"))
                .andExpect(jsonPath("$.findings[0].message").value(org.hamcrest.Matchers.containsString("회사의 비용이 아니에요")));

        // 오답 예시에 없는 비용 계정(819 임차료) → 범용 WRONG_ACCOUNT + 받침에 맞는 조사("부채예요")
        updateDraft(entryId, 0, entryBody("2026-01-12", line("DEBIT", "819", null, 250_000), line("CREDIT", "103", null, 250_000)));
        attemptAction(attemptId, "submit")
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.findings[0].type").value("WRONG_ACCOUNT"))
                .andExpect(jsonPath("$.findings[0].message").value("819 임차료는 비용 계정인데, 이 거래에서 바뀌는 것은 부채예요."));

        // 계정은 맞고 금액이 50,000원 부족 → 범용 WRONG_AMOUNT + 차액 표시
        updateDraft(entryId, 1, entryBody("2026-01-12", line("DEBIT", "254", null, 200_000), line("CREDIT", "103", null, 200_000)));
        attemptAction(attemptId, "submit")
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.findings[0].type").value("WRONG_AMOUNT"))
                .andExpect(jsonPath("$.findings[0].message").value(org.hamcrest.Matchers.containsString("차액 50,000원")));
    }

    @Test
    @DisplayName("제출 API는 선택 본문(answer)을 받아 attempt_submission.submitted_answer에 저장한다 (ADR-0017)")
    void submitStoresOptionalAnswerBody() throws Exception {
        Long attemptId = start("withholding-tax-payment");

        // 본문 없이 제출 → submitted_answer는 null
        attemptAction(attemptId, "submit").andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        // 빈 객체 본문 → submitted_answer는 null
        mvc.perform(post("/api/v1/learning/attempts/" + attemptId + "/submit").session(learner).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        // answer 값이 있으면 JSON 그대로 저장 (quiz·lookup 분석용)
        mvc.perform(post("/api/v1/learning/attempts/" + attemptId + "/submit").session(learner).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"choice-2\"}"))
                .andExpect(status().isOk());

        java.util.List<String> stored = jdbc.sql(
                        "select submitted_answer from attempt_submission where attempt_id = ? order by id")
                .param(attemptId).query((rs, n) -> rs.getString("submitted_answer")).list();
        assertThat(stored).containsExactly(null, null, "\"choice-2\"");
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
                // 증빙: 통장 거래내역과 입금 통지
                .andExpect(jsonPath("$.evidence", hasSize(2)))
                .andExpect(jsonPath("$.evidence[0].type").value("bank"))
                .andExpect(jsonPath("$.evidence[0].body.rows[0][3]").value(12_000_000))
                .andExpect(jsonPath("$.evidence[1].title").value("입금 통지"))
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

    // ------------------------------------------------------------------ 끝낸 미션: 다시 보기 · 다시 풀기

    private String mission(String key, String action) {
        return "/api/v1/learning/enrollments/" + enrollmentId + "/missions/" + key + "/" + action;
    }

    /** 원천세 납부를 한 번 반려된 뒤 통과한다 */
    private void passWithholdingTax() throws Exception {
        Long attemptId = start("withholding-tax-payment");
        attemptAction(attemptId, "hint");
        Long entryId = createDraft(entryBody("2026-01-12", line("DEBIT", "103", null, 250_000), line("CREDIT", "254", null, 250_000)));
        attemptAction(attemptId, "submit").andExpect(jsonPath("$.passed").value(false));
        updateDraft(entryId, 0, entryBody("2026-01-12", line("DEBIT", "254", null, 250_000), line("CREDIT", "103", null, 250_000)));
        attemptAction(attemptId, "submit").andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    @DisplayName("다시 보기: 끝내기 전에는 409 (정답이 들어 있다), 끝낸 뒤에는 정답 분개 · 해설 · 내가 반려됐던 사유를 준다")
    void reviewShowsAnswerAndExplanationOnlyAfterFinishing() throws Exception {
        mvc.perform(get(mission("withholding-tax-payment", "review")).session(learner))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MISSION_NOT_FINISHED"));
        Long attemptId = start("withholding-tax-payment");
        mvc.perform(get(mission("withholding-tax-payment", "review")).session(learner)).andExpect(status().isConflict());
        // 진행 중에 보이는 응답에는 해설이 없다
        assertThat(mvc.perform(get("/api/v1/learning/attempts/" + attemptId).session(learner)).andReturn()
                .getResponse().getContentAsString()).doesNotContain("explanation").doesNotContain("흔한 실수");
        attemptAction(attemptId, "reveal").andExpect(status().isOk());
        mvc.perform(get(mission("withholding-tax-payment", "review")).session(learner))
                .andExpect(jsonPath("$.status").value("REVEALED"))
                .andExpect(jsonPath("$.rejections", hasSize(0)));

        Long second = start("accounts-payable-other-payment");
        createDraft(entryBody("2026-01-15", line("DEBIT", "253", "00603", 1_500_000), line("CREDIT", "103", null, 1_500_000)));
        attemptAction(second, "submit").andExpect(jsonPath("$.passed").value(false));
        attemptAction(second, "reveal").andExpect(status().isOk());

        mvc.perform(get(mission("accounts-payable-other-payment", "review")).session(learner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("미지급금 지급"))
                .andExpect(jsonPath("$.chapterTitle").value("전표의 기본"))
                .andExpect(jsonPath("$.evidence[1].title").value("이체 확인증"))
                .andExpect(jsonPath("$.answer", hasSize(2)))
                .andExpect(jsonPath("$.answer[0].side").value("DEBIT"))
                .andExpect(jsonPath("$.answer[0].accountName").value("미지급금"))
                .andExpect(jsonPath("$.answer[0].partnerName").value("(주)케이렌탈"))
                .andExpect(jsonPath("$.answer[0].amount").value(1_500_000))
                .andExpect(jsonPath("$.answer[1].accountName").value("보통예금"))
                .andExpect(jsonPath("$.explanation").value(org.hamcrest.Matchers.containsString("흔한 실수")))
                .andExpect(jsonPath("$.hints", hasSize(2)))
                .andExpect(jsonPath("$.submissions").value(1))
                .andExpect(jsonPath("$.rejections", hasSize(1)))
                .andExpect(jsonPath("$.rejections[0][0]").isString())
                .andExpect(jsonPath("$.practices").value(0));

        // 남의 미션은 볼 수 없다
        AppUser other = createUser("other", PASSWORD, null, null);
        mvc.perform(get(mission("accounts-payable-other-payment", "review")).session(login(other.getLoginId(), PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("다시 풀기(연습): 채점은 똑같이 하지만 통과해도 전기하지 않고 연습 전표를 지운다 — 장부는 그대로다")
    void retryGradesWithoutTouchingTheLedger() throws Exception {
        mvc.perform(post(mission("withholding-tax-payment", "retry")).session(learner).with(xsrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MISSION_NOT_FINISHED"));
        passWithholdingTax();
        long cash = balance("103", null);
        long postedBefore = jdbc.sql("select count(*) from journal_entry where company_id = ? and status = 'POSTED'")
                .param(companyId).query(Long.class).single();

        String json = mvc.perform(post(mission("withholding-tax-payment", "retry")).session(learner).with(xsrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.practice").value(true))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.hints", hasSize(0)))
                .andReturn().getResponse().getContentAsString();
        Long practiceId = ((Number) JsonPath.read(json, "$.id")).longValue();
        // 본 시도의 결과는 그대로이고, 지금 연습 중이라는 것만 더해진다
        mvc.perform(get("/api/v1/learning/enrollments/" + enrollmentId).session(learner))
                .andExpect(jsonPath("$.chapters[0].missions[0].status").value("PASSED"))
                .andExpect(jsonPath("$.chapters[0].missions[0].practiceAttemptId").value(practiceId));
        // 같은 미션을 또 누르면 같은 연습, 연습 중에는 다음 미션을 시작할 수 없다
        mvc.perform(post(mission("withholding-tax-payment", "retry")).session(learner).with(xsrf()))
                .andExpect(jsonPath("$.id").value(practiceId));
        mvc.perform(post(mission("accounts-payable-other-payment", "start")).session(learner).with(xsrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MISSION_IN_PROGRESS"));

        attemptAction(practiceId, "submit").andExpect(jsonPath("$.findings[0].type").value("NO_ENTRY"));
        Long entryId = createDraft(entryBody("2026-01-12", line("DEBIT", "817", null, 250_000), line("CREDIT", "103", null, 250_000)));
        attemptAction(practiceId, "submit")
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.findings[0].type").value("MISTAKE"))
                .andExpect(jsonPath("$.findings[0].message").value(org.hamcrest.Matchers.containsString("회사의 비용이 아니에요")));
        updateDraft(entryId, 0, entryBody("2026-01-12", line("DEBIT", "254", null, 250_000), line("CREDIT", "103", null, 250_000)));
        attemptAction(practiceId, "submit")
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.attempt.status").value("PASSED"));

        // 연습 전표는 사라지고 장부는 연습 전과 같다
        mvc.perform(get(entries() + "/" + entryId).session(learner)).andExpect(status().isNotFound());
        assertThat(balance("103", null)).isEqualTo(cash);
        assertThat(jdbc.sql("select count(*) from journal_entry where company_id = ? and status = 'POSTED'")
                .param(companyId).query(Long.class).single()).isEqualTo(postedBefore);
        mvc.perform(get("/api/v1/learning/enrollments/" + enrollmentId).session(learner))
                .andExpect(jsonPath("$.chapters[0].missions[0].status").value("PASSED"))
                .andExpect(jsonPath("$.chapters[0].missions[0].practiceAttemptId").doesNotExist());
        mvc.perform(get(mission("withholding-tax-payment", "review")).session(learner))
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.submissions").value(2))       // 본 시도의 기록만
                .andExpect(jsonPath("$.hintsUsed").value(1))
                .andExpect(jsonPath("$.rejections", hasSize(1)))
                .andExpect(jsonPath("$.practices").value(1));

        // 연습을 끝냈으니 다음 미션을 시작할 수 있다
        start("accounts-payable-other-payment");
    }

    @Test
    @DisplayName("연습을 그만두면(정답 보고 넘어가기) 쓰던 전표만 지워지고, 정답 전표가 또 들어가지 않는다")
    void quittingPracticeLeavesTheLedgerAlone() throws Exception {
        passWithholdingTax();
        long cash = balance("103", null);
        String json = mvc.perform(post(mission("withholding-tax-payment", "retry")).session(learner).with(xsrf()))
                .andReturn().getResponse().getContentAsString();
        Long practiceId = ((Number) JsonPath.read(json, "$.id")).longValue();
        createDraft(entryBody("2026-01-12", line("DEBIT", "817", null, 250_000), line("CREDIT", "103", null, 250_000)));

        attemptAction(practiceId, "reveal").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVEALED"));

        assertThat(jdbc.sql("select count(*) from journal_entry where company_id = ? and status = 'DRAFT'")
                .param(companyId).query(Long.class).single()).isZero();
        assertThat(balance("103", null)).isEqualTo(cash);
        assertThat(balance("254", null)).isZero();
        mvc.perform(get("/api/v1/learning/enrollments/" + enrollmentId).session(learner))
                .andExpect(jsonPath("$.chapters[0].missions[0].status").value("PASSED")); // 본 시도는 통과한 그대로
    }

    // ------------------------------------------------------------------ 장 다시 시작 · 회차(run)

    private ResultActions enrollCourse(MockHttpSession session, String courseKey) throws Exception {
        return mvc.perform(post("/api/v1/learning/enrollments").session(session).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content(json("courseKey", courseKey)));
    }

    private ResultActions restart(MockHttpSession session, Long id, String chapterKey) throws Exception {
        return mvc.perform(post("/api/v1/learning/enrollments/" + id + "/restart")
                .param("chapter", chapterKey).session(session).with(xsrf()));
    }

    private Map<String, Long> postedBalanceMap(Long targetCompanyId) {
        Map<String, Long> result = new TreeMap<>();
        jdbc.sql("""
                        select a.code || '|' || coalesce(p.code, '') as k,
                               sum(l.debit_amount) - sum(l.credit_amount) as net
                          from journal_line l
                          join journal_entry e on e.id = l.entry_id
                          join account a on a.id = l.account_id
                          left join partner p on p.id = l.partner_id
                         where e.company_id = ? and e.status = 'POSTED'
                         group by a.code, p.code
                        having sum(l.debit_amount) - sum(l.credit_amount) <> 0
                        """)
                .param(targetCompanyId)
                .query((rs, n) -> result.put(rs.getString("k"), rs.getLong("net")))
                .list();
        return result;
    }

    @Test
    @DisplayName("장 다시 시작: 새 회사가 생기고 이전 회차·진행 중 시도는 ABANDONED, 옛 회사 전표와 제출 기록은 남는다")
    void restartCreatesNewRunAndPreservesOldCompanyAndSubmissions() throws Exception {
        // 아직 1장을 시작하기 전에는 다시 시작할 수 없다 (409)
        mvc.perform(get("/api/v1/learning/enrollments/" + enrollmentId).session(learner))
                .andExpect(jsonPath("$.chapters[0].canRestart").value(false));
        restart(learner, enrollmentId, "ch1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAPTER_NOT_STARTED"));

        // 1번 미션 통과, 2번 미션은 오답 제출 1회 + 임시 전표를 남겨 둔 채 진행 중
        passWithholdingTax();
        Long inProgressAttemptId = start("accounts-payable-other-payment");
        Long strayDraftId = createDraft(entryBody("2026-01-15",
                line("DEBIT", "253", "00603", 1_500_000), line("CREDIT", "103", null, 1_500_000)));
        attemptAction(inProgressAttemptId, "submit").andExpect(jsonPath("$.passed").value(false));

        Long oldCompanyId = companyId;
        long oldPostedCount = jdbc.sql("select count(*) from journal_entry where company_id = ? and status = 'POSTED'")
                .param(oldCompanyId).query(Long.class).single();

        mvc.perform(get("/api/v1/learning/enrollments/" + enrollmentId).session(learner))
                .andExpect(jsonPath("$.chapters[0].canRestart").value(true));

        String restartedJson = restart(learner, enrollmentId, "ch1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(enrollmentId))
                .andExpect(jsonPath("$.chapters[0].missions[0].status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.chapters[0].missions[1].status").value("NOT_STARTED"))
                .andReturn().getResponse().getContentAsString();
        Long newCompanyId = ((Number) JsonPath.read(restartedJson, "$.companyId")).longValue();
        assertThat(newCompanyId).isNotEqualTo(oldCompanyId);

        // 회차 상태: 이전 회차 ABANDONED(ended_at 채워짐), 새 회차 ACTIVE(ended_at null)
        assertThat(jdbc.sql("select status from sandbox_run where enrollment_id = ? and company_id = ?")
                .params(enrollmentId, oldCompanyId).query(String.class).single()).isEqualTo("ABANDONED");
        assertThat(jdbc.sql("select ended_at is not null from sandbox_run where enrollment_id = ? and company_id = ?")
                .params(enrollmentId, oldCompanyId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select status from sandbox_run where enrollment_id = ? and company_id = ?")
                .params(enrollmentId, newCompanyId).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("select start_chapter from sandbox_run where enrollment_id = ? and company_id = ?")
                .params(enrollmentId, newCompanyId).query(String.class).single()).isEqualTo("ch1");

        // 이전 회사의 전기 전표와 임시 전표는 그대로 남는다
        assertThat(jdbc.sql("select count(*) from journal_entry where company_id = ? and status = 'POSTED'")
                .param(oldCompanyId).query(Long.class).single()).isEqualTo(oldPostedCount);
        assertThat(jdbc.sql("select status from journal_entry where id = ?")
                .param(strayDraftId).query(String.class).single()).isEqualTo("DRAFT");

        // 진행 중이던 시도는 ABANDONED, 그 제출 기록은 그대로 남는다
        assertThat(jdbc.sql("select status from mission_attempt where id = ?")
                .param(inProgressAttemptId).query(String.class).single()).isEqualTo("ABANDONED");
        assertThat(jdbc.sql("select finished_at is not null from mission_attempt where id = ?")
                .param(inProgressAttemptId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select count(*) from attempt_submission where attempt_id = ?")
                .param(inProgressAttemptId).query(Long.class).single()).isEqualTo(1);
        attemptAction(inProgressAttemptId, "submit")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MISSION_ALREADY_FINISHED"));

        // 새 회사는 1장 시작 전(기초잔액만) 상태이고, 1번 미션을 본 시도 유니크에 걸리지 않고 처음부터 다시 풀 수 있다
        companyId = newCompanyId;
        assertThat(balance("103", null)).isEqualTo(30_000_000);
        assertThat(balance("254", null)).isEqualTo(-250_000);
        passWithholdingTax();
        assertThat(balance("254", null)).isZero();
        assertThat(jdbc.sql("select count(*) from mission_attempt where enrollment_id = ? and mission_key = 'withholding-tax-payment' and not practice")
                .param(enrollmentId).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("2장부터 다시 시작: 새 회사의 장부 = 기초잔액 + 1장 정답, 1장은 완료·다시 보기·다시 풀기가 유지되고 2장은 처음부터 푼다")
    void restartAtSecondChapterReplaysPriorChaptersAndPreservesReviews() throws Exception {
        String enrollJson = enrollCourse(learner, "two-chapters-test")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Long twoId = ((Number) JsonPath.read(enrollJson, "$.id")).longValue();
        Long firstCompanyId = ((Number) JsonPath.read(enrollJson, "$.companyId")).longValue();

        // 1장 완료: m1-tax(힌트 1회 + 반려 1회 후 통과), m2-payable-other(정답 보고 넘어가기)
        Long m1Attempt = ((Number) JsonPath.read(
                mvc.perform(post("/api/v1/learning/enrollments/" + twoId + "/missions/m1-tax/start")
                                .session(learner).with(xsrf()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        attemptAction(m1Attempt, "hint").andExpect(status().isOk());
        Long wrongEntry = ((Number) JsonPath.read(
                mvc.perform(post("/api/v1/companies/" + firstCompanyId + "/journal-entries")
                                .session(learner).with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                                .content(entryBody("2026-01-12", line("DEBIT", "103", null, 250_000), line("CREDIT", "254", null, 250_000))))
                        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        attemptAction(m1Attempt, "submit").andExpect(jsonPath("$.passed").value(false));
        mvc.perform(put("/api/v1/companies/" + firstCompanyId + "/journal-entries/" + wrongEntry)
                        .session(learner).with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0," + entryBody("2026-01-12",
                                line("DEBIT", "254", null, 250_000), line("CREDIT", "103", null, 250_000)).substring(1)))
                .andExpect(status().isOk());
        attemptAction(m1Attempt, "submit").andExpect(jsonPath("$.passed").value(true));

        Long m2Attempt = ((Number) JsonPath.read(
                mvc.perform(post("/api/v1/learning/enrollments/" + twoId + "/missions/m2-payable-other/start")
                                .session(learner).with(xsrf()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        attemptAction(m2Attempt, "reveal").andExpect(status().isOk());

        // 1장 완료 직후 장부 잔액 (기초잔액 + #1 + #2 정답)
        Map<String, Long> expectedCheckpointBalances = postedBalanceMap(firstCompanyId);

        // 2장 첫 미션을 시작하기 전에는 2장으로 다시 시작할 수 없다 (409)
        restart(learner, twoId, "ch2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAPTER_NOT_STARTED"));

        // 2장 첫 미션 시작 후 반려 1회 + 임시 전표 남김
        Long m3Attempt = ((Number) JsonPath.read(
                mvc.perform(post("/api/v1/learning/enrollments/" + twoId + "/missions/m3-receivable/start")
                                .session(learner).with(xsrf()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        attemptAction(m3Attempt, "submit").andExpect(jsonPath("$.passed").value(false));

        // 2장부터 다시 시작
        String restarted = restart(learner, twoId, "ch2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapters[0].missions[0].status").value("PASSED"))
                .andExpect(jsonPath("$.chapters[0].missions[1].status").value("REVEALED"))
                .andExpect(jsonPath("$.chapters[1].missions[0].status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.chapters[1].missions[1].status").value("NOT_STARTED"))
                .andReturn().getResponse().getContentAsString();
        Long secondCompanyId = ((Number) JsonPath.read(restarted, "$.companyId")).longValue();
        assertThat(secondCompanyId).isNotEqualTo(firstCompanyId);

        // 새 회차의 장부 잔액 = 기초잔액 + 앞 장(1장) 미션의 정답
        assertThat(postedBalanceMap(secondCompanyId)).isEqualTo(expectedCheckpointBalances);
        assertThat(jdbc.sql("select count(*) from journal_entry where company_id = ? and status = 'POSTED'")
                .param(secondCompanyId).query(Long.class).single()).isEqualTo(3); // 기초잔액 + #1 + #2

        // 앞 장(1장) 미션은 이미 끝낸 것으로 본다: start는 409, review는 이전 회차의 마지막 본 시도 기록을 보여 준다
        mvc.perform(post("/api/v1/learning/enrollments/" + twoId + "/missions/m1-tax/start")
                        .session(learner).with(xsrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MISSION_ALREADY_FINISHED"));
        mvc.perform(get("/api/v1/learning/enrollments/" + twoId + "/missions/m1-tax/review").session(learner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.hintsUsed").value(1))
                .andExpect(jsonPath("$.submissions").value(2))
                .andExpect(jsonPath("$.rejections", hasSize(1)));

        // 앞 장(1장) 미션의 다시 풀기(연습)도 새 회차에서 장부를 건드리지 않고 동작한다
        Long practiceId = ((Number) JsonPath.read(
                mvc.perform(post("/api/v1/learning/enrollments/" + twoId + "/missions/m1-tax/retry")
                                .session(learner).with(xsrf()))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.practice").value(true))
                        .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(post("/api/v1/companies/" + secondCompanyId + "/journal-entries")
                        .session(learner).with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody("2026-01-12", line("DEBIT", "254", null, 250_000), line("CREDIT", "103", null, 250_000))))
                .andExpect(status().isCreated());
        attemptAction(practiceId, "submit").andExpect(jsonPath("$.passed").value(true));
        assertThat(postedBalanceMap(secondCompanyId)).isEqualTo(expectedCheckpointBalances);

        // 2장 첫 미션을 앞 장 잠금 없이 바로 시작하고 통과할 수 있다
        Long newM3Attempt = ((Number) JsonPath.read(
                mvc.perform(post("/api/v1/learning/enrollments/" + twoId + "/missions/m3-receivable/start")
                                .session(learner).with(xsrf()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(newM3Attempt).isNotEqualTo(m3Attempt);
        mvc.perform(post("/api/v1/companies/" + secondCompanyId + "/journal-entries")
                        .session(learner).with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody("2026-02-25", line("DEBIT", "103", null, 12_000_000), line("CREDIT", "108", "00101", 12_000_000))))
                .andExpect(status().isCreated());
        attemptAction(newM3Attempt, "submit").andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    @DisplayName("남의 수강이나 없는 장으로 다시 시작하려 하면 404")
    void restartRejectsOthersAndUnknownChapter() throws Exception {
        start("withholding-tax-payment");
        AppUser other = createUser("other-restart", PASSWORD, null, null);
        MockHttpSession otherSession = login(other.getLoginId(), PASSWORD);

        restart(otherSession, enrollmentId, "ch1").andExpect(status().isNotFound());
        restart(learner, enrollmentId, "no-such-chapter").andExpect(status().isNotFound());
    }
}
