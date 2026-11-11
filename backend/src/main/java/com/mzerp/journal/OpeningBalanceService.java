package com.mzerp.journal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mzerp.common.error.ApiException;
import com.mzerp.common.error.ErrorCode;
import com.mzerp.master.account.Account;
import com.mzerp.master.account.AccountRepository;
import com.mzerp.master.account.StatementType;
import com.mzerp.master.fiscal.FiscalCalendar;
import com.mzerp.master.fiscal.FiscalYear;
import com.mzerp.master.fiscal.FiscalYearService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전기분 재무상태표 = 회계연도 첫날의 기초잔액(OPENING) 전표 한 장 (ADR-0004, 회계 노트 03).
 * <ul>
 *   <li>첫 회계연도에만 직접 입력한다. 다음 해부터는 연도 마감(차기이월)이 만든다</li>
 *   <li>재무상태표 계정(자산·부채·자본)만 넣을 수 있다</li>
 *   <li>다른 전표와 같은 생애를 따른다: 임시일 때는 통째로 바꿔 쓰고, 전기하면 불변 → 정정은 역분개 후 다시 입력</li>
 * </ul>
 * 차대 일치, 거래처 필수 같은 전표 공통 규칙은 {@link JournalEntry}와 {@link JournalService}의 것을 그대로 쓴다.
 */
@Service
public class OpeningBalanceService {

    static final String DESCRIPTION = "전기분 재무상태표 이월";

    private final JournalEntryRepository entries;
    private final JournalService journal;
    private final AccountRepository accounts;
    private final FiscalYearService fiscalYears;
    private final FiscalCalendar calendar;
    private final EntryNumberGenerator numbers;

    public OpeningBalanceService(JournalEntryRepository entries, JournalService journal, AccountRepository accounts,
                                 FiscalYearService fiscalYears, FiscalCalendar calendar, EntryNumberGenerator numbers) {
        this.entries = entries;
        this.journal = journal;
        this.accounts = accounts;
        this.fiscalYears = fiscalYears;
        this.calendar = calendar;
        this.numbers = numbers;
    }

    /** @param entry 살아 있는(역분개되지 않은) 기초잔액 전표. 아직 입력하지 않았으면 null */
    public record OpeningView(int termNo, LocalDate startDate, LocalDate endDate, JournalService.EntryView entry) {
    }

    @Transactional(readOnly = true)
    public OpeningView get(Long companyId, int year) {
        FiscalYear fiscalYear = firstFiscalYear(companyId, year);
        return view(fiscalYear, active(companyId, fiscalYear).orElse(null));
    }

    /** 기초잔액을 저장한다. 임시 전표가 있으면 라인을 통째로 바꾸고, 없으면 새로 만든다 (임시 상태). */
    @Transactional
    public OpeningView save(Long companyId, Long userId, int year, List<JournalService.LineInput> lines) {
        FiscalYear fiscalYear = firstFiscalYear(companyId, year);
        requireBalanceSheetAccounts(companyId, lines);
        List<JournalEntry.LineDraft> drafts = journal.resolveLines(companyId, lines);
        calendar.requireOpenPeriod(companyId, fiscalYear.getStartDate());

        Optional<JournalEntry> existing = active(companyId, fiscalYear);
        if (existing.isEmpty()) {
            int entryNo = numbers.next(companyId, fiscalYear.getStartDate());
            JournalEntry created = JournalEntry.draft(companyId, fiscalYear.getId(), fiscalYear.getStartDate(), entryNo,
                    EntryType.OPENING, DESCRIPTION, userId, drafts);
            return view(fiscalYear, entries.save(created));
        }
        JournalEntry entry = existing.get();
        if (entry.getStatus() == EntryStatus.POSTED) {
            throw new ApiException(ErrorCode.ENTRY_IMMUTABLE,
                    "기초잔액은 이미 전기되었습니다. 고치려면 역분개한 뒤 다시 입력하세요");
        }
        entry.replace(fiscalYear.getId(), entry.getEntryDate(), entry.getEntryNo(), DESCRIPTION, drafts);
        return view(fiscalYear, entries.saveAndFlush(entry));
    }

    // ------------------------------------------------------------------ 내부

    /**
     * 그 해에 시작하는 회계연도를 찾고, 회사의 첫 회계연도인지 확인한다.
     * 둘째 해부터 기초잔액을 손으로 넣으면 전기 말 잔액과 어긋난 장부가 만들어질 수 있다.
     */
    private FiscalYear firstFiscalYear(Long companyId, int year) {
        List<FiscalYear> all = fiscalYears.list(companyId);
        FiscalYear found = all.stream().filter(y -> y.getStartDate().getYear() == year).findFirst()
                .orElseThrow(() -> ApiException.notFound(year + "년에 시작하는 회계연도"));
        if (!found.getId().equals(all.getFirst().getId())) {
            throw new ApiException(ErrorCode.OPENING_NOT_FIRST_YEAR,
                    "제" + found.getTermNo() + "기의 기초잔액은 직전 회계연도 마감에서 이월됩니다");
        }
        return found;
    }

    /** 살아 있는 기초잔액 전표: 그 회계연도의 OPENING 전표 중 역분개되지 않은 것 (최대 한 장). */
    private Optional<JournalEntry> active(Long companyId, FiscalYear fiscalYear) {
        return entries.findByCompanyIdAndFiscalYearIdAndEntryType(companyId, fiscalYear.getId(), EntryType.OPENING).stream()
                .filter(e -> !entries.existsByReversalOfId(e.getId()))
                .findFirst();
    }

    /** 수익·비용 계정은 기초잔액에 넣을 수 없다. 없는 계정은 뒤의 공통 검사가 잡는다. */
    private void requireBalanceSheetAccounts(Long companyId, List<JournalService.LineInput> lines) {
        Map<String, Account> byCode = accounts.findByCompanyIdOrderByCode(companyId).stream()
                .collect(Collectors.toMap(Account::getCode, Function.identity()));
        for (int i = 0; i < lines.size(); i++) {
            Account account = byCode.get(lines.get(i).accountCode());
            if (account != null && account.getStatement() != StatementType.BS) {
                throw new ApiException(ErrorCode.ACCOUNT_NOT_POSTABLE,
                        (i + 1) + "번째 라인: " + account.getCode() + " " + account.getName()
                                + "은(는) 재무상태표 계정이 아니라서 기초잔액에 넣을 수 없습니다");
            }
        }
    }

    private OpeningView view(FiscalYear fiscalYear, JournalEntry entry) {
        return new OpeningView(fiscalYear.getTermNo(), fiscalYear.getStartDate(), fiscalYear.getEndDate(),
                entry == null ? null : journal.view(entry));
    }
}
