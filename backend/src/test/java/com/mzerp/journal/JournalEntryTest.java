package com.mzerp.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.mzerp.common.error.ApiException;
import com.mzerp.common.error.ErrorCode;
import com.mzerp.common.money.Money;
import com.mzerp.journal.JournalEntry.LineDraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 전표 한 장의 회계 규칙 — DB 없이 도는 단위 테스트 (회계 노트 02 §3).
 * 계정 id는 의미 없는 숫자다: 103 보통예금 → 103L 처럼 읽기 쉽게 맞췄다.
 */
@DisplayName("JournalEntry: 전표 한 장의 규칙")
class JournalEntryTest {

    private static final LocalDate DATE = LocalDate.of(2026, 3, 10);

    private static LineDraft debit(long accountId, long won) {
        return new LineDraft(Side.DEBIT, accountId, null, Money.won(won), null);
    }

    private static LineDraft credit(long accountId, long won) {
        return new LineDraft(Side.CREDIT, accountId, null, Money.won(won), null);
    }

    private static JournalEntry draft(List<LineDraft> lines) {
        return JournalEntry.draft(1L, 1L, DATE, 1, EntryType.GENERAL, "받을어음 만기", 1L, lines);
    }

    private static ErrorCode codeOf(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e.code();
        }
        throw new AssertionError("ApiException이 나야 합니다");
    }

    @Test
    @DisplayName("차변 합계 = 대변 합계면 임시 전표가 만들어진다 (시나리오 #6)")
    void balancedEntryIsCreated() {
        JournalEntry entry = draft(List.of(debit(103, 5_000_000), credit(110, 5_000_000)));

        assertThat(entry.getStatus()).isEqualTo(EntryStatus.DRAFT);
        assertThat(entry.debitTotal()).isEqualTo(Money.won(5_000_000));
        assertThat(entry.creditTotal()).isEqualTo(Money.won(5_000_000));
        assertThat(entry.getLines()).extracting(JournalLine::getLineNo).containsExactly(1, 2);
    }

    @Test
    @DisplayName("여러 줄이어도 합계만 같으면 된다 (시나리오 #16 급여: 차변 2줄, 대변 2줄)")
    void multiLineEntry() {
        JournalEntry entry = draft(List.of(
                debit(503, 18_000_000), debit(801, 12_000_000),
                credit(254, 2_400_000), credit(103, 27_600_000)));
        assertThat(entry.debitTotal()).isEqualTo(entry.creditTotal());
    }

    @Test
    @DisplayName("차대가 다르면 JOURNAL_UNBALANCED")
    void unbalancedIsRejected() {
        assertThat(codeOf(() -> draft(List.of(debit(103, 5_000_000), credit(110, 4_999_999)))))
                .isEqualTo(ErrorCode.JOURNAL_UNBALANCED);
    }

    @Test
    @DisplayName("라인이 2줄 미만이면 JOURNAL_TOO_FEW_LINES")
    void tooFewLines() {
        assertThat(codeOf(() -> draft(List.of(debit(103, 1_000))))).isEqualTo(ErrorCode.JOURNAL_TOO_FEW_LINES);
        assertThat(codeOf(() -> draft(List.of()))).isEqualTo(ErrorCode.JOURNAL_TOO_FEW_LINES);
    }

    @Test
    @DisplayName("0원·음수·범위 초과 금액은 LINE_AMOUNT_INVALID")
    void invalidAmounts() {
        assertThat(codeOf(() -> draft(List.of(debit(103, 0), credit(110, 0))))).isEqualTo(ErrorCode.LINE_AMOUNT_INVALID);
        assertThat(codeOf(() -> draft(List.of(debit(103, -100), credit(110, -100))))).isEqualTo(ErrorCode.LINE_AMOUNT_INVALID);
        long tooBig = Money.MAX_API_WON + 1;
        assertThat(codeOf(() -> draft(List.of(debit(103, tooBig), credit(110, tooBig))))).isEqualTo(ErrorCode.LINE_AMOUNT_INVALID);
    }

    @Test
    @DisplayName("임시 전표는 통째로 바꿀 수 있고, 바꿀 때도 같은 규칙을 검사한다")
    void draftCanBeReplaced() {
        JournalEntry entry = draft(List.of(debit(103, 5_000_000), credit(110, 5_000_000)));
        entry.replace(1L, DATE, 1, "수정", List.of(debit(103, 3_000_000), credit(110, 3_000_000)));
        assertThat(entry.debitTotal()).isEqualTo(Money.won(3_000_000));

        assertThat(codeOf(() -> entry.replace(1L, DATE, 1, "수정", List.of(debit(103, 1), credit(110, 2)))))
                .isEqualTo(ErrorCode.JOURNAL_UNBALANCED);
    }

    @Test
    @DisplayName("전기하면 POSTED가 되고, 이후에는 수정·재전기 불가 (ENTRY_IMMUTABLE)")
    void postedEntryIsImmutable() {
        JournalEntry entry = draft(List.of(debit(103, 5_000_000), credit(110, 5_000_000)));
        entry.post(7L, Instant.parse("2026-03-10T09:00:00Z"));

        assertThat(entry.getStatus()).isEqualTo(EntryStatus.POSTED);
        assertThat(entry.getPostedBy()).isEqualTo(7L);
        assertThat(codeOf(() -> entry.replace(1L, DATE, 1, "x", List.of(debit(103, 1), credit(110, 1)))))
                .isEqualTo(ErrorCode.ENTRY_IMMUTABLE);
        assertThat(codeOf(() -> entry.post(7L, Instant.now()))).isEqualTo(ErrorCode.ENTRY_IMMUTABLE);
        assertThat(codeOf(entry::requireDraft)).isEqualTo(ErrorCode.ENTRY_IMMUTABLE);
    }

    @Test
    @DisplayName("역분개 라인 = 원전표 라인의 차대만 뒤집은 것 → 합치면 모든 계정 변화가 0")
    void reversalFlipsEverySide() {
        JournalEntry original = draft(List.of(debit(814, 450_000), credit(101, 450_000)));
        original.post(1L, Instant.now());

        List<LineDraft> reversed = original.reversalDrafts();

        assertThat(reversed).containsExactly(credit(814, 450_000), debit(101, 450_000));
    }

    @Test
    @DisplayName("임시 전표는 역분개하지 않는다 (ENTRY_NOT_POSTED) — 그냥 고치거나 지우면 된다")
    void draftCannotBeReversed() {
        JournalEntry entry = draft(List.of(debit(103, 1_000), credit(110, 1_000)));
        assertThatThrownBy(entry::reversalDrafts)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_NOT_POSTED));
    }
}
