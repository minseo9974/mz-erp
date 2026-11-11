package com.mzerp.learning.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.mzerp.support.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

/**
 * 골든 테스트: 시나리오 회사의 1년(기초잔액 1 + 거래 38 + 결산 9)을 ERP로 재생하면 장부가 정답과 같아야 한다.
 * <p>
 * 정답({@code scenario/answers-2026.json})은 앱과 무관한 파이썬 스크립트가 같은 시나리오 파일로 계산한 값이고,
 * 별도 코드로 한 번 더 검산했다 (docs/scenario/verification-2026.md). 엔진을 고쳐서 이 테스트가 깨지면 머지하지 않는다.
 * <p>
 * 장부 조회 기능(3단계)이 아직 없어서 잔액은 테스트 안의 SQL로 집계한다 — 전기된 전표의 라인만 더한다 (불변식 5).
 */
@DisplayName("골든 테스트: 시나리오 1년치를 재생하면 장부가 정답과 같다")
class ScenarioGoldenTest extends IntegrationTest {

    @Autowired
    private ScenarioReplayer replayer;

    @Autowired
    private JdbcClient jdbc;

    private final Scenario scenario = ScenarioLoader.load("mz-electronics-2026");

    private Long companyId;
    private Long userId;

    @BeforeEach
    void setUp() throws Exception {
        companyId = createCompany(loginAsAdmin(), scenario.companyName());
        userId = users.findByLoginId(ADMIN_LOGIN).orElseThrow().getId();
    }

    @Test
    @DisplayName("시나리오 파일: 거래처 12 · 기초잔액 23줄 · 거래 38 · 결산 9")
    void scenarioFileIsReadCompletely() {
        assertThat(scenario.termNo()).isEqualTo(6);
        assertThat(scenario.partners()).hasSize(12);
        assertThat(scenario.opening().lines()).hasSize(23);
        assertThat(scenario.transactions()).hasSize(38);
        assertThat(scenario.closing()).hasSize(9);
        assertThat(scenario.transaction(12).lines()).extracting(Scenario.Line::partnerCode)
                .containsExactly("00102", "00102", null, null);
    }

    @Test
    @DisplayName("1년치 전부: 계정별 차변·대변 합계, 거래처별 합계, 순이익, 자산 = 부채 + 자본")
    @SuppressWarnings("unchecked")
    void fullYearMatchesAnswers() throws Exception {
        replayer.prepare(companyId, userId, scenario);
        replayer.replayTransactions(companyId, userId, scenario, Integer.MAX_VALUE);
        replayer.replayClosing(companyId, userId, scenario);

        Map<String, Object> answers;
        try (InputStream in = new ClassPathResource("scenario/answers-2026.json").getInputStream()) {
            answers = JsonMapper.builder().build().readValue(in, Map.class);
        }

        // 전표 48장이 모두 전기되었다
        assertThat(jdbc.sql("select count(*) from journal_entry where company_id = ? and status = 'POSTED'")
                .param(companyId).query(Long.class).single()).isEqualTo(48);

        // 합계잔액시산표: 계정별 차변 합계·대변 합계
        Map<String, List<Long>> expectedTrialBalance = new TreeMap<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) answers.get("trialBalance")) {
            expectedTrialBalance.put((String) row.get("code"), List.of(num(row.get("debit")), num(row.get("credit"))));
        }
        Map<String, List<Long>> trialBalance = new TreeMap<>();
        jdbc.sql("""
                        select a.code, sum(l.debit_amount) as debit, sum(l.credit_amount) as credit
                          from journal_line l
                          join journal_entry e on e.id = l.entry_id
                          join account a on a.id = l.account_id
                         where e.company_id = ? and e.status = 'POSTED'
                         group by a.code
                        """)
                .param(companyId)
                .query((rs, n) -> trialBalance.put(rs.getString("code"), List.of(rs.getLong("debit"), rs.getLong("credit"))))
                .list();
        assertThat(trialBalance).isEqualTo(expectedTrialBalance);

        // 거래처별 합계 (거래처원장의 바탕)
        Map<String, List<Long>> expectedPartners = new TreeMap<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) answers.get("partnerBalances")) {
            expectedPartners.put(row.get("account") + "/" + row.get("partner"), List.of(num(row.get("debit")), num(row.get("credit"))));
        }
        Map<String, List<Long>> partnerBalances = new TreeMap<>();
        jdbc.sql("""
                        select a.code as account, p.code as partner, sum(l.debit_amount) as debit, sum(l.credit_amount) as credit
                          from journal_line l
                          join journal_entry e on e.id = l.entry_id
                          join account a on a.id = l.account_id
                          join partner p on p.id = l.partner_id
                         where e.company_id = ? and e.status = 'POSTED'
                         group by a.code, p.code
                        """)
                .param(companyId)
                .query((rs, n) -> partnerBalances.put(rs.getString("account") + "/" + rs.getString("partner"),
                        List.of(rs.getLong("debit"), rs.getLong("credit"))))
                .list();
        assertThat(partnerBalances).isEqualTo(expectedPartners);

        // 재무제표의 뼈대: 대분류별 잔액 (차변 − 대변)
        Map<String, Long> byCategory = new TreeMap<>();
        jdbc.sql("""
                        select a.category, sum(l.debit_amount) - sum(l.credit_amount) as net
                          from journal_line l
                          join journal_entry e on e.id = l.entry_id
                          join account a on a.id = l.account_id
                         where e.company_id = ? and e.status = 'POSTED'
                         group by a.category
                        """)
                .param(companyId)
                .query((rs, n) -> byCategory.put(rs.getString("category"), rs.getLong("net")))
                .list();
        Map<String, Object> totals = (Map<String, Object>) answers.get("totals");
        long netIncome = -byCategory.get("REVENUE") - byCategory.get("EXPENSE"); // 수익(대변 잔액) − 비용(차변 잔액)
        assertThat(netIncome).isEqualTo(num(totals.get("netIncome"))).isEqualTo(6_285_000);
        assertThat(byCategory.get("ASSET")).isEqualTo(num(totals.get("assets"))).isEqualTo(140_166_520);
        assertThat(-byCategory.get("LIABILITY")).isEqualTo(num(totals.get("liabilities")));
        assertThat(-byCategory.get("EQUITY") + netIncome).isEqualTo(num(totals.get("equity")));
        assertThat(byCategory.get("ASSET")).isEqualTo(-byCategory.get("LIABILITY") - byCategory.get("EQUITY") + netIncome);
    }

    @Test
    @DisplayName("체크포인트: 거래 5번까지만 재생한 회사에 6번(받을어음 만기)을 대신 처리하면 그 거래처의 어음 잔액이 0이 된다")
    void replaysUpToACheckpointAndThenSingleTransactions() {
        replayer.prepare(companyId, userId, scenario);
        assertThat(replayer.replayTransactions(companyId, userId, scenario, 5)).hasSize(5);
        assertThat(noteReceivable("00101")).isEqualTo(5_000_000); // 기초잔액 그대로

        replayer.replayTransaction(companyId, userId, scenario, 6);
        assertThat(noteReceivable("00101")).isZero();
    }

    private long noteReceivable(String partnerCode) {
        return jdbc.sql("""
                        select coalesce(sum(l.debit_amount) - sum(l.credit_amount), 0)
                          from journal_line l
                          join journal_entry e on e.id = l.entry_id
                          join account a on a.id = l.account_id
                          join partner p on p.id = l.partner_id
                         where e.company_id = ? and e.status = 'POSTED' and a.code = '110' and p.code = ?
                        """)
                .params(companyId, partnerCode).query(Long.class).single();
    }

    private static long num(Object value) {
        return ((Number) value).longValue();
    }
}
