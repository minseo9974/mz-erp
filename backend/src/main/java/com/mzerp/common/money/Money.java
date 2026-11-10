package com.mzerp.common.money;

/**
 * 원 단위 정수 금액 (ADR-0002). double을 쓰지 않으므로 0.1 + 0.2 같은 오차가 생기지 않는다.
 * 더하기·빼기에서 long 범위를 넘으면 조용히 틀린 값이 되지 않도록 예외를 던진다(Math.addExact).
 */
public record Money(long won) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    /** API가 받을 수 있는 최대 금액: 브라우저 Number가 정확히 표현하는 정수 범위 (docs/design/api.md) */
    public static final long MAX_API_WON = 9_007_199_254_740_991L;

    public static Money won(long won) {
        return won == 0 ? ZERO : new Money(won);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(won, other.won));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(won, other.won));
    }

    public Money negate() {
        return new Money(Math.negateExact(won));
    }

    public boolean isZero() {
        return won == 0;
    }

    public boolean isPositive() {
        return won > 0;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(won, other.won);
    }

    @Override
    public String toString() {
        return String.format("%,d원", won);
    }
}
