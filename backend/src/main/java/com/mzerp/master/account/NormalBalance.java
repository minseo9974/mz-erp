package com.mzerp.master.account;

/**
 * 정상잔액 방향. 잔액 = (DEBIT이면 차변합 − 대변합, CREDIT이면 대변합 − 차변합).
 * 대분류만으로 정하지 않는 이유: 대손충당금·감가상각누계액은 자산이지만 대변 잔액이다 (회계 노트 01).
 */
public enum NormalBalance {
    DEBIT, CREDIT
}
