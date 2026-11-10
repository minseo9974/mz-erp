package com.mzerp.journal;

/**
 * 전표 종류.
 * <ul>
 *   <li>GENERAL 일반전표 (출금·입금·대체) — 2단계</li>
 *   <li>SALES_PURCHASE 매입매출전표 — 4단계</li>
 *   <li>OPENING 기초잔액(전기분 재무상태표·차기이월) — ADR-0004</li>
 *   <li>CLOSING 결산정리 — 5단계</li>
 *   <li>REVERSAL 역분개 — 원전표를 가리킨다</li>
 * </ul>
 */
public enum EntryType {
    GENERAL, SALES_PURCHASE, OPENING, CLOSING, REVERSAL
}
