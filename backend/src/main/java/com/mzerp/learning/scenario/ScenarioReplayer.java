package com.mzerp.learning.scenario;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.mzerp.journal.JournalService;
import com.mzerp.journal.OpeningBalanceService;
import com.mzerp.master.fiscal.FiscalYearService;
import com.mzerp.master.partner.PartnerService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시나리오를 회사에 "재생"한다: 화면에서 사람이 하듯 ERP의 공개 서비스로 전표를 작성하고 전기한다 (ADR-0014).
 * <p>
 * 세 곳에서 같은 엔진을 쓴다.
 * <ol>
 *   <li>골든 테스트 — 전부 재생한 장부가 정답과 같은가</li>
 *   <li>체크포인트 — 실습자가 3장을 시작할 때 2장까지 맞게 처리된 회사를 만들어 준다</li>
 *   <li>정답 대신 처리 — 막힌 미션의 전표를 시스템이 대신 넣는다</li>
 * </ol>
 * 테이블을 직접 건드리지 않으므로, 재생한 전표도 차대 검증·기간 검사·채번·DB 트리거를 모두 거친다.
 * <p>
 * 매입매출전표의 부가세 정보(유형·공급가액·세액)는 4단계에서 부가세 모듈이 생기면 함께 재생한다.
 * 지금은 분개 라인만 넣는다 — 장부 잔액은 이것만으로 정답과 같아진다.
 */
@Service
public class ScenarioReplayer {

    private final FiscalYearService fiscalYears;
    private final PartnerService partners;
    private final OpeningBalanceService openingBalance;
    private final JournalService journal;

    public ScenarioReplayer(FiscalYearService fiscalYears, PartnerService partners,
                            OpeningBalanceService openingBalance, JournalService journal) {
        this.fiscalYears = fiscalYears;
        this.partners = partners;
        this.openingBalance = openingBalance;
        this.journal = journal;
    }

    /**
     * 방금 만든 회사에 시나리오의 바탕을 깐다: 회계연도, 거래처, 기초잔액(전기까지).
     * 계정과목표는 회사를 만들 때 이미 복사되어 있다.
     */
    @Transactional
    public void prepare(Long companyId, Long userId, Scenario scenario) {
        fiscalYears.create(companyId, new FiscalYearService.CreateCommand(
                scenario.startDate().getYear(), scenario.startDate().getMonthValue(), scenario.termNo()));
        for (Scenario.PartnerSeed p : scenario.partners()) {
            partners.create(companyId, new PartnerService.CreateCommand(p.code(), p.name(), null, p.type()));
        }
        var opening = openingBalance.save(companyId, userId, scenario.startDate().getYear(),
                toInputs(scenario.opening()));
        journal.post(companyId, opening.entry().id(), userId);
    }

    /** 거래 1번부터 {@code upToNo}번까지 날짜 순서로 작성·전기한다. 만든 전표 id를 돌려준다. */
    @Transactional
    public List<Long> replayTransactions(Long companyId, Long userId, Scenario scenario, int upToNo) {
        return replay(companyId, userId, scenario.transactions().stream().filter(e -> e.no() <= upToNo).toList());
    }

    /** 거래 한 건만 작성·전기한다 (정답 대신 처리). */
    @Transactional
    public Long replayTransaction(Long companyId, Long userId, Scenario scenario, int no) {
        return replay(companyId, userId, List.of(scenario.transaction(no))).getFirst();
    }

    /** 결산정리 분개 전부. 5단계에서 결산 모듈이 생기면 그 모듈이 만든 전표와 비교하는 기준이 된다. */
    @Transactional
    public List<Long> replayClosing(Long companyId, Long userId, Scenario scenario) {
        return replay(companyId, userId, scenario.closing());
    }

    private List<Long> replay(Long companyId, Long userId, List<Scenario.Entry> entries) {
        List<Long> ids = new ArrayList<>();
        // 날짜가 같으면 시나리오 번호 순 — 전표번호가 재생할 때마다 같게 매겨진다
        for (Scenario.Entry e : entries.stream()
                .sorted(Comparator.comparing(Scenario.Entry::date).thenComparingInt(Scenario.Entry::no)).toList()) {
            var created = journal.create(companyId, userId, e.type(),
                    new JournalService.EntryInput(e.date(), e.description(), toInputs(e)));
            journal.post(companyId, created.id(), userId);
            ids.add(created.id());
        }
        return ids;
    }

    private static List<JournalService.LineInput> toInputs(Scenario.Entry entry) {
        return entry.lines().stream()
                .map(l -> new JournalService.LineInput(l.side(), l.accountCode(), l.partnerCode(), l.amount(), entry.description()))
                .toList();
    }
}
