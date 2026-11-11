package com.mzerp.learning.grading;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.mzerp.journal.Side;
import com.mzerp.learning.grading.Grader.AccountInfo;
import com.mzerp.learning.grading.Grader.Finding;
import com.mzerp.learning.grading.Grader.FindingType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 채점기는 DB 없이 "기대 변화"와 "실제 변화"만으로 판정한다 (ADR-0013).
 * 기준 거래 — 시나리오 #5: (주)한빛전자 외상매출금 12,000,000 보통예금 회수
 *   (차) 103 보통예금 12,000,000 / (대) 108 외상매출금 12,000,000 (00101)
 */
@DisplayName("채점기: 장부의 변화로 판정하고, 틀린 이유를 유형으로 짚는다")
class GraderTest {

    private static final Map<String, AccountInfo> ACCOUNTS = Map.of(
            "101", new AccountInfo("101", "현금", "ASSET", "당좌자산"),
            "103", new AccountInfo("103", "보통예금", "ASSET", "당좌자산"),
            "108", new AccountInfo("108", "외상매출금", "ASSET", "당좌자산"),
            "110", new AccountInfo("110", "받을어음", "ASSET", "당좌자산"),
            "206", new AccountInfo("206", "기계장치", "ASSET", "유형자산"),
            "404", new AccountInfo("404", "제품매출", "REVENUE", "매출"));

    /** 라인 묶음을 장부 변화로: "D 103 12000000", "C 108 12000000 00101" */
    private static Map<Balances.Key, Long> change(String... lines) {
        Map<Balances.Key, Long> net = new TreeMap<>();
        for (String line : lines) {
            String[] p = line.split(" ");
            Balances.add(net, p[1], p.length > 3 ? p[3] : null, p[0].equals("D") ? Side.DEBIT : Side.CREDIT, Long.parseLong(p[2]));
        }
        return net;
    }

    private static final Map<Balances.Key, Long> EXPECTED = change("D 103 12000000", "C 108 12000000 00101");

    private static List<Finding> grade(String... actualLines) {
        return Grader.grade(EXPECTED, change(actualLines), ACCOUNTS);
    }

    @Test
    @DisplayName("같은 변화면 통과 — 줄 순서나 전표를 몇 장으로 나눴는지는 상관없다")
    void sameChangePasses() {
        assertThat(grade("C 108 12000000 00101", "D 103 12000000")).isEmpty();
        assertThat(grade("D 103 5000000", "C 108 5000000 00101", "D 103 7000000", "C 108 7000000 00101")).isEmpty();
    }

    @Test
    @DisplayName("틀리게 넣었다가 반대로 상쇄하고 다시 넣어도 통과 — 정정 절차를 거친 답도 정답이다")
    void correctedByReversalPasses() {
        assertThat(grade(
                "D 108 12000000 00101", "C 103 12000000",   // 처음에 거꾸로
                "C 108 12000000 00101", "D 103 12000000",   // 역분개
                "D 103 12000000", "C 108 12000000 00101"))  // 올바른 전표
                .isEmpty();
    }

    @Test
    @DisplayName("장부에 변화가 없으면 NO_ENTRY")
    void nothingEntered() {
        assertThat(grade()).extracting(Finding::type).containsExactly(FindingType.NO_ENTRY);
    }

    @Test
    @DisplayName("차변·대변이 통째로 반대면 SIDE_SWAPPED")
    void sidesSwapped() {
        assertThat(grade("C 103 12000000", "D 108 12000000 00101")).extracting(Finding::type)
                .containsExactly(FindingType.SIDE_SWAPPED);
    }

    @Test
    @DisplayName("계정·금액은 맞는데 거래처가 다르면 WRONG_PARTNER, 어느 계정인지 알려 준다")
    void wrongPartner() {
        List<Finding> findings = grade("D 103 12000000", "C 108 12000000 00102");
        assertThat(findings).extracting(Finding::type).containsExactly(FindingType.WRONG_PARTNER);
        assertThat(findings.getFirst().message()).contains("108 외상매출금");
    }

    @Test
    @DisplayName("한 계정만 다르면 WRONG_ACCOUNT — 같은 중분류면 '그 안에서 다시', 대분류가 다르면 무엇이 바뀌는 거래인지")
    void wrongAccount() {
        Finding sameSubgroup = grade("D 101 12000000", "C 108 12000000 00101").getFirst(); // 보통예금 대신 현금
        assertThat(sameSubgroup.type()).isEqualTo(FindingType.WRONG_ACCOUNT);
        assertThat(sameSubgroup.message()).contains("101 현금").contains("당좌자산인 것은 맞으니");

        Finding sameCategory = grade("D 206 12000000", "C 108 12000000 00101").getFirst(); // 보통예금 대신 기계장치
        assertThat(sameCategory.message()).contains("자산인 것은 맞지만");

        Finding otherCategory = grade("D 103 12000000", "C 404 12000000").getFirst(); // 외상매출금 대신 매출 — 매출을 두 번 잡는 실수
        assertThat(otherCategory.type()).isEqualTo(FindingType.WRONG_ACCOUNT);
        assertThat(otherCategory.message()).contains("404 제품매출").contains("수익 계정인데").contains("자산");
    }

    @Test
    @DisplayName("반려 사유는 정답 계정을 알려 주지 않는다")
    void findingsDoNotRevealTheAnswer() {
        assertThat(grade("D 101 12000000", "C 108 12000000 00101").getFirst().message()).doesNotContain("보통예금");
    }

    @Test
    @DisplayName("계정은 맞는데 금액이 다르면 WRONG_AMOUNT")
    void wrongAmount() {
        assertThat(grade("D 103 1200000", "C 108 1200000 00101")).extracting(Finding::type)
                .containsExactly(FindingType.WRONG_AMOUNT);
    }

    @Test
    @DisplayName("그 밖의 차이는 UNEXPECTED_CHANGE — 관계없는 계정이 바뀐 것과 바뀌어야 할 계정이 그대로인 것을 나눠 말한다")
    void unexpectedChange() {
        List<Finding> findings = grade("D 110 5000000 00101", "C 404 5000000");
        assertThat(findings).extracting(Finding::type).containsOnly(FindingType.UNEXPECTED_CHANGE);
        assertThat(findings).extracting(Finding::message)
                .anyMatch(m -> m.contains("110 받을어음") && m.contains("404 제품매출"))
                .anyMatch(m -> m.contains("2개가 아직 그대로"));
    }
}
