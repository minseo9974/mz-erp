package com.mzerp.master.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("표준 계정과목표(data/seed/accounts.csv) 자체 검증")
class StandardChartTest {

    private final List<StandardChart.Row> rows = StandardChart.load();
    private final Map<String, StandardChart.Row> byCode =
            rows.stream().collect(Collectors.toMap(StandardChart.Row::code, Function.identity()));

    @Test
    @DisplayName("계정코드는 중복되지 않는다")
    void codesAreUnique() {
        assertThat(byCode).hasSize(rows.size());
    }

    @Test
    @DisplayName("차감계정은 존재하는 계정을 가리키고, 대상과 정상잔액 방향이 반대다")
    void contraAccountsAreConsistent() {
        rows.stream().filter(r -> r.contraOf() != null).forEach(r -> {
            StandardChart.Row target = byCode.get(r.contraOf());
            assertThat(target).as(r.code() + "의 차감 대상").isNotNull();
            assertThat(r.normalBalance()).as(r.code()).isNotEqualTo(target.normalBalance());
        });
    }

    @Test
    @DisplayName("차감계정·자기주식 말고는 자산·비용=차변, 부채·자본·수익=대변")
    void normalBalanceFollowsCategory() {
        rows.stream()
                .filter(r -> r.contraOf() == null && !r.system() && !r.code().equals("383"))
                .forEach(r -> {
                    NormalBalance expected = (r.category() == AccountCategory.ASSET || r.category() == AccountCategory.EXPENSE)
                            ? NormalBalance.DEBIT : NormalBalance.CREDIT;
                    assertThat(r.normalBalance()).as(r.code() + " " + r.name()).isEqualTo(expected);
                });
    }

    @Test
    @DisplayName("헤더가 다르거나 칸 수가 틀리면 바로 실패한다")
    void rejectsMalformedCsv() {
        assertThatThrownBy(() -> StandardChart.parse(List.of("code,name")))
                .isInstanceOf(IllegalStateException.class);
    }
}
