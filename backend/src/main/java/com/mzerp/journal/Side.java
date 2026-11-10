package com.mzerp.journal;

/** 차변(왼쪽) / 대변(오른쪽). 거래의 8요소는 회계 노트 01. */
public enum Side {
    DEBIT, CREDIT;

    public Side opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
