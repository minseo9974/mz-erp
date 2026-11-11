package com.mzerp.journal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import com.mzerp.common.error.ApiException;
import com.mzerp.common.error.ErrorCode;
import com.mzerp.common.money.Money;
import com.mzerp.master.account.Account;
import com.mzerp.master.account.AccountRepository;
import com.mzerp.master.fiscal.FiscalCalendar;
import com.mzerp.master.partner.Partner;
import com.mzerp.master.partner.PartnerRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전표 유스케이스: 작성 · 수정 · 삭제 · 전기 · 역분개 · 조회.
 * <p>
 * 전표 한 장 안에서 끝나는 규칙(차대 일치 등)은 {@link JournalEntry}가, 다른 데이터를 봐야 하는 규칙
 * (계정 사용 가능 여부, 거래처 필수, 회계기간 열림)은 이 클래스가 확인한다.
 */
@Service
public class JournalService {

    private final JournalEntryRepository entries;
    private final AccountRepository accounts;
    private final PartnerRepository partners;
    private final FiscalCalendar calendar;
    private final EntryNumberGenerator numbers;

    @PersistenceContext
    private EntityManager em;

    public JournalService(JournalEntryRepository entries, AccountRepository accounts, PartnerRepository partners,
                          FiscalCalendar calendar, EntryNumberGenerator numbers) {
        this.entries = entries;
        this.accounts = accounts;
        this.partners = partners;
        this.calendar = calendar;
        this.numbers = numbers;
    }

    // ------------------------------------------------------------------ 입력·출력 모델

    /** 화면·API에서 받는 라인: 계정·거래처는 코드로 받는다 (docs/design/api.md). */
    public record LineInput(Side side, String accountCode, String partnerCode, Long amount, String memo) {
    }

    public record EntryInput(LocalDate entryDate, String description, List<LineInput> lines) {
    }

    public record LineView(int lineNo, Side side, String accountCode, String accountName,
                           String partnerCode, String partnerName, long amount, String memo) {
    }

    public record EntryView(Long id, LocalDate entryDate, int entryNo, EntryType entryType, EntryStatus status,
                            String description, long debitTotal, long creditTotal, int version,
                            Long reversalOfId, Long reversedById, String reversalReason,
                            Instant postedAt, List<LineView> lines) {
    }

    // ------------------------------------------------------------------ 쓰기

    /** 일반전표 작성 (화면·API의 기본 경로). */
    @Transactional
    public EntryView create(Long companyId, Long userId, EntryInput input) {
        return create(companyId, userId, EntryType.GENERAL, input);
    }

    /**
     * 유형을 지정해 작성한다. 화면 밖에서 전표를 만드는 경로(시나리오 재생, 이후 매입매출·결산 모듈)용.
     * 기초잔액(OPENING)과 역분개(REVERSAL)는 규칙이 더 있어서 전용 경로로만 만든다
     * ({@link OpeningBalanceService}, {@link #reverse}).
     */
    @Transactional
    public EntryView create(Long companyId, Long userId, EntryType type, EntryInput input) {
        if (type == EntryType.OPENING || type == EntryType.REVERSAL) {
            throw new IllegalArgumentException(type + " 전표는 전용 경로로만 만듭니다");
        }
        List<JournalEntry.LineDraft> drafts = resolveLines(companyId, input.lines());
        FiscalCalendar.OpenPeriod period = calendar.requireOpenPeriod(companyId, input.entryDate());
        int entryNo = numbers.next(companyId, input.entryDate());
        JournalEntry entry = JournalEntry.draft(companyId, period.fiscalYearId(), input.entryDate(), entryNo,
                type, input.description(), userId, drafts);
        return view(entries.save(entry));
    }

    @Transactional
    public EntryView update(Long companyId, Long entryId, int expectedVersion, EntryInput input) {
        JournalEntry entry = load(companyId, entryId);
        if (entry.getVersion() != expectedVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "요청 version " + expectedVersion + ", 현재 version " + entry.getVersion());
        }
        entry.requireDraft();
        if (entry.getEntryType() != EntryType.GENERAL) {
            // 기초잔액 전표를 여기서 고치면 "재무상태표 계정만"이라는 규칙을 건너뛰게 된다
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "일반전표 화면에서는 일반전표만 수정할 수 있습니다");
        }
        List<JournalEntry.LineDraft> drafts = resolveLines(companyId, input.lines());
        FiscalCalendar.OpenPeriod period = calendar.requireOpenPeriod(companyId, input.entryDate());
        // 날짜가 바뀌면 그 날짜의 새 번호를 받는다 (번호는 일자별 연번)
        int entryNo = input.entryDate().equals(entry.getEntryDate())
                ? entry.getEntryNo()
                : numbers.next(companyId, input.entryDate());
        entry.replace(period.fiscalYearId(), input.entryDate(), entryNo, input.description(), drafts);
        int before = entry.getVersion();
        JournalEntry saved = entries.saveAndFlush(entry);
        // 라인 목록은 전표의 "반대편"(mappedBy)이라 라인만 바뀌면 JPA가 version을 올리지 않는다.
        // 그러면 같은 임시 전표를 두 사람이 동시에 고칠 때 나중 저장이 앞 저장을 조용히 덮어쓴다 → 그때만 강제로 올린다.
        // 이미 다른 사람이 올렸으면 이 UPDATE가 0행이 되어 VERSION_CONFLICT(409)로 끝난다.
        if (saved.getVersion() == before) {
            em.lock(saved, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        }
        return view(saved);
    }

    @Transactional
    public void delete(Long companyId, Long entryId) {
        JournalEntry entry = load(companyId, entryId);
        entry.requireDraft();
        entries.delete(entry);
    }

    /** 전기. 임시 전표를 만든 뒤 그 달이 마감됐을 수 있으므로 기간을 다시 확인한다. */
    @Transactional
    public EntryView post(Long companyId, Long entryId, Long userId) {
        JournalEntry entry = load(companyId, entryId);
        calendar.requireOpenPeriod(companyId, entry.getEntryDate());
        entry.post(userId, Instant.now());
        return view(entries.saveAndFlush(entry));
    }

    /** 여러 전표를 한 번에 전기. 하나라도 실패하면 전부 롤백된다 (한 트랜잭션). */
    @Transactional
    public List<EntryView> postAll(Long companyId, List<Long> entryIds, Long userId) {
        List<EntryView> posted = new ArrayList<>();
        for (Long id : entryIds) {
            posted.add(post(companyId, id, userId));
        }
        return posted;
    }

    /**
     * 역분개: 원전표와 차대가 반대인 전표를 만들어 바로 전기한다 (회계 노트 02 §5).
     * @param reversalDate 비우면 원전표 날짜. 원전표의 달이 마감됐으면 정정 날짜를 지정해야 한다.
     *                     원전표일보다 빠를 수 없다 (REVERSAL_DATE_INVALID).
     */
    @Transactional
    public EntryView reverse(Long companyId, Long entryId, Long userId, LocalDate reversalDate, String reason) {
        JournalEntry original = load(companyId, entryId);
        if (original.getEntryType() == EntryType.REVERSAL) {
            throw new ApiException(ErrorCode.ENTRY_ALREADY_REVERSED,
                    "역분개 전표는 다시 역분개할 수 없습니다. 필요한 분개를 새 전표로 입력하세요");
        }
        if (entries.existsByReversalOfId(original.getId())) {
            throw new ApiException(ErrorCode.ENTRY_ALREADY_REVERSED,
                    "전표 " + original.getEntryDate() + "-" + original.getEntryNo() + "은(는) 이미 역분개되었습니다");
        }
        LocalDate date = reversalDate != null ? reversalDate : original.getEntryDate();
        // 취소가 원거래보다 먼저 장부에 나오면 그 사이 기간의 잔액이 실제와 달라진다
        if (date.isBefore(original.getEntryDate())) {
            throw new ApiException(ErrorCode.REVERSAL_DATE_INVALID,
                    "원전표일 " + original.getEntryDate() + ", 역분개일 " + date);
        }
        FiscalCalendar.OpenPeriod period = calendar.requireOpenPeriod(companyId, date);
        int entryNo = numbers.next(companyId, date);

        JournalEntry reversal = JournalEntry.reversalOf(original, period.fiscalYearId(), date, entryNo, reason, userId);
        // DB 트리거는 "전기된 전표에 라인 추가"를 막으므로, 임시 상태로 라인까지 저장한 뒤 전기한다
        entries.saveAndFlush(reversal);
        reversal.post(userId, Instant.now());
        return view(entries.saveAndFlush(reversal));
    }

    // ------------------------------------------------------------------ 읽기

    @Transactional(readOnly = true)
    public EntryView get(Long companyId, Long entryId) {
        return view(load(companyId, entryId));
    }

    @Transactional(readOnly = true)
    public List<EntryView> list(Long companyId, LocalDate from, LocalDate to, EntryStatus status, EntryType type) {
        if (from.isAfter(to)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "조회 시작일이 종료일보다 늦습니다");
        }
        List<JournalEntry> found = entries.findByCompanyIdAndEntryDateBetweenOrderByEntryDateAscEntryNoAsc(companyId, from, to)
                .stream()
                .filter(e -> status == null || e.getStatus() == status)
                .filter(e -> type == null || e.getEntryType() == type)
                .toList();
        return views(companyId, found);
    }

    // ------------------------------------------------------------------ 내부

    private JournalEntry load(Long companyId, Long entryId) {
        return entries.findByIdAndCompanyId(entryId, companyId)
                .orElseThrow(() -> ApiException.notFound("전표 " + entryId));
    }

    /**
     * 코드로 받은 라인을 내부 id로 바꾸면서 다른 데이터가 필요한 규칙을 확인한다.
     * - 계정: 이 회사의 계정, 사용 중, 시스템 계정 아님 (ACCOUNT_NOT_POSTABLE)
     * - 거래처: 거래처 필수 계정이면 반드시 (PARTNER_REQUIRED), 입력했다면 이 회사의 거래처
     */
    List<JournalEntry.LineDraft> resolveLines(Long companyId, List<LineInput> inputs) {
        if (inputs == null) {
            return List.of();
        }
        Map<String, Account> accountByCode = accounts.findByCompanyIdOrderByCode(companyId).stream()
                .collect(Collectors.toMap(Account::getCode, Function.identity()));
        List<JournalEntry.LineDraft> drafts = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            LineInput in = inputs.get(i);
            String where = (i + 1) + "번째 라인";
            Account account = accountByCode.get(in.accountCode());
            if (account == null) {
                throw ApiException.notFound(where + "의 계정 " + in.accountCode());
            }
            if (account.isSystem() || !account.isActive()) {
                throw new ApiException(ErrorCode.ACCOUNT_NOT_POSTABLE,
                        where + ": " + account.getCode() + " " + account.getName()
                                + (account.isSystem() ? "은(는) 결산 전용 시스템 계정입니다" : "은(는) 사용 중지된 계정입니다"));
            }
            Long partnerId = null;
            if (in.partnerCode() != null && !in.partnerCode().isBlank()) {
                Partner partner = partners.findByCompanyIdAndCode(companyId, in.partnerCode())
                        .orElseThrow(() -> ApiException.notFound(where + "의 거래처 " + in.partnerCode()));
                partnerId = partner.getId();
            } else if (account.isRequiresPartner()) {
                throw new ApiException(ErrorCode.PARTNER_REQUIRED,
                        where + ": " + account.getCode() + " " + account.getName() + "은(는) 거래처를 입력해야 합니다");
            }
            drafts.add(new JournalEntry.LineDraft(in.side(), account.getId(), partnerId,
                    in.amount() == null ? null : Money.won(in.amount()), in.memo()));
        }
        return drafts;
    }

    EntryView view(JournalEntry entry) {
        return views(entry.getCompanyId(), List.of(entry)).getFirst();
    }

    /** 여러 전표를 응답 모델로. 계정·거래처 이름은 한 번에 읽어 N+1 조회를 피한다. */
    private List<EntryView> views(Long companyId, List<JournalEntry> list) {
        Set<Long> accountIds = new HashSet<>();
        Set<Long> partnerIds = new HashSet<>();
        list.forEach(e -> e.getLines().forEach(l -> {
            accountIds.add(l.getAccountId());
            if (l.getPartnerId() != null) partnerIds.add(l.getPartnerId());
        }));
        Map<Long, Account> accountById = accounts.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        Map<Long, Partner> partnerById = partners.findAllById(partnerIds).stream()
                .collect(Collectors.toMap(Partner::getId, Function.identity()));
        List<Long> postedIds = list.stream().filter(e -> e.getStatus() == EntryStatus.POSTED).map(JournalEntry::getId).toList();
        Map<Long, Long> reversedBy = postedIds.isEmpty() ? Map.of()
                : entries.findByReversalOfIdIn(postedIds).stream()
                        .collect(Collectors.toMap(JournalEntry::getReversalOfId, JournalEntry::getId));

        return list.stream().map(e -> {
            Long reversedById = reversedBy.get(e.getId());
            List<LineView> lines = e.getLines().stream().map(l -> {
                Account a = accountById.get(l.getAccountId());
                Partner p = l.getPartnerId() == null ? null : partnerById.get(l.getPartnerId());
                return new LineView(l.getLineNo(), l.side(), a.getCode(), a.getName(),
                        p == null ? null : p.getCode(), p == null ? null : p.getName(), l.amount().won(), l.getMemo());
            }).toList();
            return new EntryView(e.getId(), e.getEntryDate(), e.getEntryNo(), e.getEntryType(), e.getStatus(),
                    e.getDescription(), e.debitTotal().won(), e.creditTotal().won(), e.getVersion(),
                    e.getReversalOfId(), reversedById, e.getReversalReason(), e.getPostedAt(), lines);
        }).toList();
    }
}
