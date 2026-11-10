package com.mzerp.journal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.mzerp.common.error.ApiException;
import com.mzerp.common.error.ErrorCode;
import com.mzerp.common.money.Money;

/**
 * 전표 (집합 루트). 전표 한 장에 대한 회계 규칙은 전부 이 클래스 안에서 지킨다.
 * <ol>
 *   <li>라인 2줄 이상, 금액은 양의 정수, 차변 합계 = 대변 합계 → {@link #validate(List)}</li>
 *   <li>DRAFT만 수정 가능, 전기하면 POSTED로 불변 → {@link #replace}, {@link #post}</li>
 *   <li>정정은 역분개 → {@link #reversalDrafts()}</li>
 * </ol>
 * 계정이 사용 가능한지, 거래처가 필요한지, 기간이 열려 있는지처럼 <b>다른 데이터가 필요한 규칙</b>은
 * {@link JournalService}가 확인한다. 해설: docs/walkthrough/02-journal-engine.md
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntry {

    /** 라인 입력값. 저장 전 검증 단위이자 역분개 재료. */
    public record LineDraft(Side side, Long accountId, Long partnerId, Money amount, String memo) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "fiscal_year_id", nullable = false)
    private Long fiscalYearId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "entry_no", nullable = false)
    private int entryNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false)
    private EntryType entryType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryStatus status = EntryStatus.DRAFT;

    private String description;

    @Column(name = "reversal_of_id", updatable = false)
    private Long reversalOfId;

    @Column(name = "reversal_reason", updatable = false)
    private String reversalReason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "posted_by")
    private Long postedBy;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Version
    private int version;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<JournalLine> lines = new ArrayList<>();

    protected JournalEntry() {
    }

    /** 임시(DRAFT) 전표 생성. 라인 규칙을 통과해야 만들어진다. */
    public static JournalEntry draft(Long companyId, Long fiscalYearId, LocalDate entryDate, int entryNo,
                                     EntryType type, String description, Long createdBy, List<LineDraft> lines) {
        validate(lines);
        JournalEntry entry = new JournalEntry();
        entry.companyId = companyId;
        entry.fiscalYearId = fiscalYearId;
        entry.entryDate = entryDate;
        entry.entryNo = entryNo;
        entry.entryType = type;
        entry.description = description;
        entry.createdBy = createdBy;
        entry.setLines(lines);
        return entry;
    }

    /** 역분개 전표 생성 (DRAFT로 만든 뒤 서비스가 바로 전기한다 — DB 트리거가 전기된 전표에 라인 추가를 막으므로). */
    static JournalEntry reversalOf(JournalEntry original, Long fiscalYearId, LocalDate date, int entryNo,
                                   String reason, Long createdBy) {
        JournalEntry reversal = draft(original.companyId, fiscalYearId, date, entryNo, EntryType.REVERSAL,
                "[역분개] " + (original.description == null ? "" : original.description), createdBy,
                original.reversalDrafts());
        reversal.reversalOfId = original.id;
        reversal.reversalReason = reason;
        return reversal;
    }

    // ------------------------------------------------------------------ 규칙

    /** 전표 한 장이 성립하기 위한 규칙 (회계 노트 02 §3). DB 트리거와 같은 규칙을 앱에서 먼저 검사한다. */
    static void validate(List<LineDraft> lines) {
        if (lines == null || lines.size() < 2) {
            throw new ApiException(ErrorCode.JOURNAL_TOO_FEW_LINES,
                    "라인이 " + (lines == null ? 0 : lines.size()) + "줄입니다");
        }
        Money debit = Money.ZERO;
        Money credit = Money.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            LineDraft line = lines.get(i);
            if (line.side() == null || line.accountId() == null) {
                throw new ApiException(ErrorCode.LINE_AMOUNT_INVALID, (i + 1) + "번째 라인: 차대 구분과 계정이 필요합니다");
            }
            if (line.amount() == null || !line.amount().isPositive() || line.amount().won() > Money.MAX_API_WON) {
                throw new ApiException(ErrorCode.LINE_AMOUNT_INVALID,
                        (i + 1) + "번째 라인: 금액은 1원 이상의 정수여야 합니다 (" + line.amount() + ")");
            }
            if (line.side() == Side.DEBIT) {
                debit = debit.plus(line.amount());
            } else {
                credit = credit.plus(line.amount());
            }
        }
        if (!debit.equals(credit)) {
            throw new ApiException(ErrorCode.JOURNAL_UNBALANCED,
                    "차변 " + debit + " / 대변 " + credit + " (차액 " + debit.minus(credit) + ")");
        }
    }

    /** 임시 전표의 날짜·적요·라인을 통째로 바꾼다. 날짜가 바뀌면 서비스가 새 번호·회계연도를 넘긴다. */
    public void replace(Long fiscalYearId, LocalDate entryDate, int entryNo, String description, List<LineDraft> lines) {
        requireDraft();
        validate(lines);
        this.fiscalYearId = fiscalYearId;
        this.entryDate = entryDate;
        this.entryNo = entryNo;
        this.description = description;
        setLines(lines);
    }

    /** 전기: 장부에 반영 확정. 이후 이 전표는 바뀌지 않는다. */
    public void post(Long userId, Instant at) {
        requireDraft();
        this.status = EntryStatus.POSTED;
        this.postedBy = userId;
        this.postedAt = at;
    }

    /** 삭제 가능 여부 (임시 전표만). */
    public void requireDraft() {
        if (status != EntryStatus.DRAFT) {
            throw new ApiException(ErrorCode.ENTRY_IMMUTABLE, "전표 " + entryDate + "-" + entryNo + "은(는) 이미 전기되었습니다");
        }
    }

    /** 이 전표의 모든 라인을 차대만 뒤집은 것. 원전표 + 역분개 = 모든 계정 잔액 변화 0. */
    List<LineDraft> reversalDrafts() {
        if (status != EntryStatus.POSTED) {
            throw new ApiException(ErrorCode.ENTRY_NOT_POSTED, "임시 전표는 역분개하지 말고 직접 고치거나 삭제하세요");
        }
        return getLines().stream().map(JournalLine::toReversedDraft).toList();
    }

    private void setLines(List<LineDraft> drafts) {
        lines.clear();
        for (int i = 0; i < drafts.size(); i++) {
            lines.add(new JournalLine(this, i + 1, drafts.get(i)));
        }
    }

    // ------------------------------------------------------------------ 조회

    public Money debitTotal() {
        return lines.stream().map(JournalLine::getDebit).reduce(Money.ZERO, Money::plus);
    }

    public Money creditTotal() {
        return lines.stream().map(JournalLine::getCredit).reduce(Money.ZERO, Money::plus);
    }

    public List<JournalLine> getLines() {
        return lines.stream().sorted(Comparator.comparingInt(JournalLine::getLineNo)).toList();
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public Long getFiscalYearId() { return fiscalYearId; }
    public LocalDate getEntryDate() { return entryDate; }
    public int getEntryNo() { return entryNo; }
    public EntryType getEntryType() { return entryType; }
    public EntryStatus getStatus() { return status; }
    public String getDescription() { return description; }
    public Long getReversalOfId() { return reversalOfId; }
    public String getReversalReason() { return reversalReason; }
    public Long getCreatedBy() { return createdBy; }
    public Long getPostedBy() { return postedBy; }
    public Instant getPostedAt() { return postedAt; }
    public int getVersion() { return version; }
}
