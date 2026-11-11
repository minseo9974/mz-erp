package com.mzerp.learning.scenario;

import java.time.LocalDate;
import java.util.List;

import com.mzerp.journal.EntryType;
import com.mzerp.journal.Side;
import com.mzerp.master.partner.PartnerType;

/**
 * 시나리오 회사의 한 해: 거래처, 기초잔액, 거래, 결산정리 분개.
 * 원본은 {@code data/scenario/*.toml}이고 {@link ScenarioLoader}가 읽는다.
 * 같은 파일을 파이썬 정답 생성기(tools/scenario/compute_answers.py)도 읽어 정답 재무제표를 만든다.
 */
public record Scenario(String companyName, int termNo, LocalDate startDate, LocalDate endDate,
                       List<PartnerSeed> partners, Entry opening, List<Entry> transactions, List<Entry> closing) {

    public record PartnerSeed(String code, String name, PartnerType type) {
    }

    /** @param partnerCode 거래처가 없는 라인은 null */
    public record Line(Side side, String accountCode, long amount, String partnerCode) {
    }

    /**
     * @param no 시나리오 안의 번호 (거래 1~38, 결산 1~9). 전표번호와는 다르다 — 전표번호는 입력할 때 날짜별로 매겨진다
     */
    public record Entry(int no, LocalDate date, EntryType type, String description, List<Line> lines) {
    }

    /** 번호로 거래 하나를 찾는다 (미션이 "시나리오 거래 #3"처럼 가리킬 때). */
    public Entry transaction(int no) {
        return transactions.stream().filter(e -> e.no() == no).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("시나리오에 거래 #" + no + "이(가) 없습니다"));
    }
}
