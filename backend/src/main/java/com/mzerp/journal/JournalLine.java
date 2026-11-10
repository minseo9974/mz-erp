package com.mzerp.journal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.mzerp.common.money.Money;

/**
 * 전표 라인 한 줄. 차변 금액 또는 대변 금액 <b>한쪽만</b> 0보다 크다 (DB CHECK와 같은 규칙).
 * 생성은 {@link JournalEntry}를 통해서만 한다 — 라인이 전표 밖에서 따로 바뀌면 차대 검증을 우회할 수 있기 때문.
 */
@Entity
@Table(name = "journal_line")
public class JournalLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false, updatable = false)
    private JournalEntry entry;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "partner_id")
    private Long partnerId;

    @Column(name = "debit_amount", nullable = false)
    private Money debit;

    @Column(name = "credit_amount", nullable = false)
    private Money credit;

    private String memo;

    protected JournalLine() {
    }

    JournalLine(JournalEntry entry, int lineNo, JournalEntry.LineDraft draft) {
        this.entry = entry;
        this.lineNo = lineNo;
        this.accountId = draft.accountId();
        this.partnerId = draft.partnerId();
        this.debit = draft.side() == Side.DEBIT ? draft.amount() : Money.ZERO;
        this.credit = draft.side() == Side.CREDIT ? draft.amount() : Money.ZERO;
        this.memo = draft.memo();
    }

    public Side side() {
        return debit.isPositive() ? Side.DEBIT : Side.CREDIT;
    }

    /** 이 라인의 금액 (차변이든 대변이든 양수) */
    public Money amount() {
        return debit.isPositive() ? debit : credit;
    }

    /** 같은 계정·거래처·금액에 차대만 반대인 라인 (역분개용) */
    JournalEntry.LineDraft toReversedDraft() {
        return new JournalEntry.LineDraft(side().opposite(), accountId, partnerId, amount(), memo);
    }

    public Long getId() { return id; }
    public int getLineNo() { return lineNo; }
    public Long getAccountId() { return accountId; }
    public Long getPartnerId() { return partnerId; }
    public Money getDebit() { return debit; }
    public Money getCredit() { return credit; }
    public String getMemo() { return memo; }
}
