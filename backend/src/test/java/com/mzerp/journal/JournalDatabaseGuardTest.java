package com.mzerp.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import com.mzerp.support.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 앱을 거치지 않고 SQL로 직접 깨진 전표를 넣어 본다 — DB 트리거·제약이 마지막 방어선인지 확인 (ADR-0006).
 * 실제로는 배치, 데이터 이관, 운영자의 직접 SQL 같은 경로가 여기에 해당한다.
 */
@DisplayName("DB 방어선: 앱을 우회한 깨진 전표는 커밋되지 않는다")
class JournalDatabaseGuardTest extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JournalService journal;

    private Long companyId;
    private Long fiscalYearId;
    private Long userId;
    private Long cash;
    private Long deposit;

    @BeforeEach
    void setUp() throws Exception {
        MockHttpSession admin = loginAsAdmin();
        companyId = createCompany(admin, "DB 방어선 테스트");
        createFiscalYear(admin, companyId, 2026, 1);
        fiscalYearId = jdbc.sql("select id from fiscal_year where company_id = ?").param(companyId).query(Long.class).single();
        userId = users.findByLoginId(ADMIN_LOGIN).orElseThrow().getId();
        cash = accountId("101");
        deposit = accountId("103");
    }

    private Long accountId(String code) {
        return jdbc.sql("select id from account where company_id = ? and code = ?").params(companyId, code).query(Long.class).single();
    }

    private Long insertEntry(String date, int no, String status) {
        return jdbc.sql("""
                        insert into journal_entry (company_id, fiscal_year_id, entry_date, entry_no, entry_type, status,
                                                   created_by, posted_by, posted_at)
                        values (?, ?, ?, ?, 'GENERAL', ?, ?, ?, ?)
                        returning id
                        """)
                .params(companyId, fiscalYearId, LocalDate.parse(date), no, status, userId,
                        "POSTED".equals(status) ? userId : null,
                        "POSTED".equals(status) ? java.sql.Timestamp.valueOf("2026-03-10 09:00:00") : null)
                .query(Long.class).single();
    }

    private void insertLine(Long entryId, int no, Long accountId, long debit, long credit) {
        jdbc.sql("insert into journal_line (entry_id, line_no, account_id, debit_amount, credit_amount) values (?, ?, ?, ?, ?)")
                .params(entryId, no, accountId, debit, credit)
                .update();
    }

    @Test
    @DisplayName("차대가 다른 전표는 커밋 시점에 거부된다 (지연 제약 트리거)")
    void unbalancedEntryIsRejectedAtCommit() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            Long id = insertEntry("2026-03-10", 901, "DRAFT");
            insertLine(id, 1, deposit, 5_000_000, 0);
            insertLine(id, 2, cash, 0, 4_000_000); // 여기까지는 통과 — 검사는 커밋 때
        })).hasStackTraceContaining("차변 5000000 ≠ 대변 4000000");

        assertThat(countEntries(901)).isZero();
    }

    @Test
    @DisplayName("라인이 1줄뿐인 전표도 커밋 시점에 거부된다")
    void singleLineEntryIsRejected() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            Long id = insertEntry("2026-03-10", 902, "DRAFT");
            insertLine(id, 1, deposit, 1_000, 0);
        })).hasStackTraceContaining("2줄 미만");
    }

    @Test
    @DisplayName("한 줄에 차변·대변 금액이 둘 다 있으면 즉시 거부 (CHECK 제약)")
    void lineWithBothSidesIsRejected() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            Long id = insertEntry("2026-03-10", 903, "DRAFT");
            insertLine(id, 1, deposit, 1_000, 1_000);
        })).hasStackTraceContaining("journal_line_check");
    }

    @Test
    @DisplayName("전기된 전표의 금액을 SQL로 바꾸거나 지울 수 없다")
    void postedEntryCannotBeTamperedWith() {
        JournalService.EntryView posted = journal.post(companyId, journal.create(companyId, userId,
                new JournalService.EntryInput(LocalDate.of(2026, 3, 10), "정상 전표", List.of(
                        new JournalService.LineInput(Side.DEBIT, "103", null, 1_000L, null),
                        new JournalService.LineInput(Side.CREDIT, "101", null, 1_000L, null)))).id(), userId);

        assertThatThrownBy(() -> jdbc.sql("update journal_line set debit_amount = 999 where entry_id = ?").param(posted.id()).update())
                .hasStackTraceContaining("라인은 추가·수정·삭제할 수 없습니다");
        assertThatThrownBy(() -> jdbc.sql("delete from journal_entry where id = ?").param(posted.id()).update())
                .hasStackTraceContaining("수정·삭제할 수 없습니다");
        assertThatThrownBy(() -> jdbc.sql("update journal_entry set description = '몰래 수정' where id = ?").param(posted.id()).update())
                .hasStackTraceContaining("수정·삭제할 수 없습니다");
    }

    @Test
    @DisplayName("마감된 기간의 날짜로는 SQL로도 전표를 넣을 수 없다")
    void closedPeriodIsGuarded() {
        jdbc.sql("update fiscal_period set status = 'CLOSED' where fiscal_year_id = ? and period_no = 3").param(fiscalYearId).update();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> insertEntry("2026-03-15", 904, "DRAFT")))
                .hasStackTraceContaining("마감된 기간");
    }

    // ------------------------------------------------------------------ V3 보강 (해설 §5-3)

    private JournalService.EntryView draftByApp(String date, long amount) {
        return journal.create(companyId, userId, new JournalService.EntryInput(LocalDate.parse(date), "정상 전표", List.of(
                new JournalService.LineInput(Side.DEBIT, "103", null, amount, null),
                new JournalService.LineInput(Side.CREDIT, "101", null, amount, null))));
    }

    @Test
    @DisplayName("임시 전표의 라인을 전기된 전표로 옮길 수 없다 — 차대가 맞는 2줄이어도")
    void linesCannotBeMovedIntoPostedEntry() {
        Long posted = journal.post(companyId, draftByApp("2026-03-10", 1_000).id(), userId).id();
        Long other = draftByApp("2026-03-10", 500).id();

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                jdbc.sql("update journal_line set entry_id = ?, line_no = line_no + 2 where entry_id = ?").params(posted, other).update()))
                .hasStackTraceContaining("다른 전표로 옮길 수 없습니다");

        assertThat(jdbc.sql("select count(*) from journal_line where entry_id = ?").param(posted).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("마감된 달에 남은 임시 전표는 SQL로도 전기할 수 없다")
    void draftInClosedPeriodCannotBePosted() {
        Long draft = draftByApp("2026-03-10", 1_000).id();
        closeMarch();

        assertThatThrownBy(() -> jdbc.sql("update journal_entry set status = 'POSTED', posted_by = ?, posted_at = now() where id = ?")
                .params(userId, draft).update())
                .hasStackTraceContaining("마감된 기간");
    }

    @Test
    @DisplayName("전표를 쓰는 트랜잭션이 끝나기 전에는 그 달을 마감할 수 없다 (검사와 커밋 사이의 틈)")
    void periodCannotBeClosedWhileAnEntryIsBeingWritten() {
        TransactionTemplate another = new TransactionTemplate(tx.getTransactionManager());
        another.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); // 다른 커넥션 = 다른 사용자

        tx.executeWithoutResult(s -> {
            Long id = insertEntry("2026-03-10", 905, "DRAFT"); // 3월 기간 행에 공유 잠금
            insertLine(id, 1, deposit, 1_000, 0);
            insertLine(id, 2, cash, 0, 1_000);

            // 아직 커밋 전 — 이때 마감하려는 쪽은 기다려야 한다. 테스트에서는 0.3초만 기다리고 포기하게 한다.
            assertThatThrownBy(() -> another.executeWithoutResult(s2 -> {
                jdbc.sql("set local lock_timeout = '300ms'").update();
                closeMarch();
            })).rootCause().isInstanceOfSatisfying(SQLException.class,
                    e -> assertThat(e.getSQLState()).isEqualTo("55P03")); // lock_not_available
        });

        closeMarch(); // 전표가 커밋된 뒤에는 마감된다
        assertThat(countEntries(905)).isEqualTo(1);
    }

    @Test
    @DisplayName("역분개 전표는 SQL로도 원전표보다 앞 날짜이거나 임시 전표를 가리킬 수 없다")
    void reversalTargetIsGuarded() {
        Long posted = journal.post(companyId, draftByApp("2026-03-10", 1_000).id(), userId).id();
        Long draft = draftByApp("2026-03-10", 500).id();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> insertReversal("2026-03-09", 906, posted)))
                .hasStackTraceContaining("원전표일(2026-03-10)보다 빠를 수 없습니다");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> insertReversal("2026-03-10", 907, draft)))
                .hasStackTraceContaining("전기된 일반 전표여야 합니다");
    }

    @Test
    @DisplayName("기초잔액(OPENING) 전표에는 SQL로도 수익·비용 계정을 넣을 수 없다 (V4)")
    void openingEntryAcceptsOnlyBalanceSheetAccounts() {
        Long sales = accountId("404");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            Long id = jdbc.sql("""
                            insert into journal_entry (company_id, fiscal_year_id, entry_date, entry_no, entry_type, status, created_by)
                            values (?, ?, date '2026-01-01', 908, 'OPENING', 'DRAFT', ?)
                            returning id
                            """)
                    .params(companyId, fiscalYearId, userId).query(Long.class).single();
            insertLine(id, 1, cash, 1_000, 0);
            insertLine(id, 2, sales, 0, 1_000);
        })).hasStackTraceContaining("재무상태표 계정만");
    }

    private void insertReversal(String date, int no, Long originalId) {
        jdbc.sql("""
                        insert into journal_entry (company_id, fiscal_year_id, entry_date, entry_no, entry_type, status,
                                                   created_by, reversal_of_id)
                        values (?, ?, ?, ?, 'REVERSAL', 'DRAFT', ?, ?)
                        """)
                .params(companyId, fiscalYearId, LocalDate.parse(date), no, userId, originalId)
                .update();
    }

    private void closeMarch() {
        jdbc.sql("update fiscal_period set status = 'CLOSED' where fiscal_year_id = ? and period_no = 3").param(fiscalYearId).update();
    }

    private long countEntries(int entryNo) {
        return jdbc.sql("select count(*) from journal_entry where company_id = ? and entry_no = ?")
                .params(companyId, entryNo).query(Long.class).single();
    }
}
