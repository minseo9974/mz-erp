package com.mzerp.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mzerp.common.money.Money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Money: 원 단위 정수 금액 (ADR-0002)")
class MoneyTest {

    @Test
    @DisplayName("더하기·빼기·부호")
    void arithmetic() {
        assertThat(Money.won(5_000_000).plus(Money.won(500_000))).isEqualTo(Money.won(5_500_000));
        assertThat(Money.won(100).minus(Money.won(300))).isEqualTo(Money.won(-200));
        assertThat(Money.won(450_000).negate()).isEqualTo(Money.won(-450_000));
        assertThat(Money.won(0)).isSameAs(Money.ZERO);
    }

    @Test
    @DisplayName("long 범위를 넘으면 조용히 틀린 값이 되지 않고 예외")
    void overflowIsDetected() {
        assertThatThrownBy(() -> Money.won(Long.MAX_VALUE).plus(Money.won(1))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("표시는 천 단위 쉼표 + 원")
    void display() {
        assertThat(Money.won(140_166_520)).hasToString("140,166,520원");
    }
}
