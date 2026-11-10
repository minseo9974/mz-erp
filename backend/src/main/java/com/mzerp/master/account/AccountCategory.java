package com.mzerp.master.account;

/** 계정 대분류. 결산 때 REVENUE·EXPENSE는 손익으로 대체되고 나머지는 차기로 이월된다. */
public enum AccountCategory {
    ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE
}
