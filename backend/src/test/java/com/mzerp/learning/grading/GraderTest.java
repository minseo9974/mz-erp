package com.mzerp.learning.grading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.mzerp.journal.EntryType;
import com.mzerp.journal.Side;
import com.mzerp.learning.course.Course;
import com.mzerp.learning.grading.Grader.AccountInfo;
import com.mzerp.learning.grading.Grader.Finding;
import com.mzerp.learning.grading.Grader.FindingType;
import com.mzerp.learning.scenario.Scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 채점기는 DB 없이 "기대 변화"와 "실제 변화"만으로 판정한다 (ADR-0013, ADR-0017).
 * 기준 거래 — 시나리오 #5: (주)한빛전자 외상매출금 12,000,000 보통예금 회수
 *   (차) 103 보통예금 12,000,000 / (대) 108 외상매출금 12,000,000 (00101)
 */
@DisplayName("채점기: 장부의 변화로 판정하고, 틀린 이유를 유형으로 짚는다")
class GraderTest {

    private static final Map<String, AccountInfo> ACCOUNTS = Map.ofEntries(
            Map.entry("101", new AccountInfo("101", "현금", "ASSET", "당좌자산")),
            Map.entry("103", new AccountInfo("103", "보통예금", "ASSET", "당좌자산")),
            Map.entry("108", new AccountInfo("108", "외상매출금", "ASSET", "당좌자산")),
            Map.entry("110", new AccountInfo("110", "받을어음", "ASSET", "당좌자산")),
            Map.entry("135", new AccountInfo("135", "부가세대급금", "ASSET", "당좌자산")),
            Map.entry("153", new AccountInfo("153", "원재료", "ASSET", "재고자산")),
            Map.entry("206", new AccountInfo("206", "기계장치", "ASSET", "유형자산")),
            Map.entry("251", new AccountInfo("251", "외상매입금", "LIABILITY", "유동부채")),
            Map.entry("253", new AccountInfo("253", "미지급금", "LIABILITY", "유동부채")),
            Map.entry("254", new AccountInfo("254", "예수금", "LIABILITY", "유동부채")),
            Map.entry("293", new AccountInfo("293", "장기차입금", "LIABILITY", "비유동부채")),
            Map.entry("404", new AccountInfo("404", "제품매출", "REVENUE", "매출")),
            Map.entry("817", new AccountInfo("817", "세금과공과", "EXPENSE", "판매비와관리비")),
            Map.entry("819", new AccountInfo("819", "임차료", "EXPENSE", "판매비와관리비")));

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
    @DisplayName("정답이 거래처를 따지지 않는 계정(보통예금)에 거래처를 붙여도 통과 — 틀린 처리가 아니다")
    void extraPartnerOnAccountWithoutPartnerPasses() {
        assertThat(grade("D 103 12000000 00101", "C 108 12000000 00101")).isEmpty();
        // 거래처를 따지는 계정(외상매출금)은 여전히 거래처까지 맞아야 한다
        assertThat(grade("D 103 12000000 00101", "C 108 12000000 00102")).extracting(Finding::type)
                .containsExactly(FindingType.WRONG_PARTNER);
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
    @DisplayName("거래처를 비운 경우(MISSING_PARTNER)와 다른 거래처를 고른 경우(WRONG_PARTNER)를 구분한다")
    void partnerMissingAndWrongAreDistinguished() {
        List<Finding> missing = grade("D 103 12000000", "C 108 12000000");
        assertThat(missing).extracting(Finding::type).containsExactly(FindingType.MISSING_PARTNER);
        assertThat(missing.getFirst().message()).contains("거래처가 비어 있어요").contains("108 외상매출금");

        List<Finding> wrong = grade("D 103 12000000", "C 108 12000000 00102");
        assertThat(wrong).extracting(Finding::type).containsExactly(FindingType.WRONG_PARTNER);
        assertThat(wrong.getFirst().message()).contains("거래처가 달라요").contains("108 외상매출금");
    }

    @Test
    @DisplayName("한 계정만 다르면 WRONG_ACCOUNT — 받침에 맞는 조사로 같은 중분류·같은 대분류·다른 대분류를 구분한다")
    void wrongAccountUsesProperKoreanParticles() {
        Finding sameSubgroup = grade("D 101 12000000", "C 108 12000000 00101").getFirst(); // 보통예금 대신 현금 (받침 있음)
        assertThat(sameSubgroup.type()).isEqualTo(FindingType.WRONG_ACCOUNT);
        assertThat(sameSubgroup.message()).isEqualTo("101 현금은 이 거래의 계정이 아니에요. 당좌자산인 것은 맞으니 그 안에서 다시 찾아보세요.");

        Finding sameCategory = grade("D 206 12000000", "C 108 12000000 00101").getFirst(); // 보통예금 대신 기계장치 (받침 없음)
        assertThat(sameCategory.message()).isEqualTo("206 기계장치는 이 거래의 계정이 아니에요. 자산인 것은 맞지만 유형자산이 아니에요.");

        Finding otherCategoryAsset = grade("D 103 12000000", "C 404 12000000").getFirst(); // 외상매출금 대신 제품매출
        assertThat(otherCategoryAsset.type()).isEqualTo(FindingType.WRONG_ACCOUNT);
        assertThat(otherCategoryAsset.message()).isEqualTo("404 제품매출은 수익 계정인데, 이 거래에서 바뀌는 것은 자산이에요.");

        // 부채(받침 없음) → "부채예요", 세금과공과(받침 없음) → "세금과공과는", 비유동부채(받침 없음) → "비유동부채가"
        Map<Balances.Key, Long> taxExpected = change("D 254 250000", "C 103 250000");
        Finding otherCategoryLiability = Grader.grade(taxExpected, change("D 817 250000", "C 103 250000"), ACCOUNTS).getFirst();
        assertThat(otherCategoryLiability.message()).isEqualTo("817 세금과공과는 비용 계정인데, 이 거래에서 바뀌는 것은 부채예요.");

        Finding sameCategoryNoBatchimSubgroup = Grader.grade(taxExpected, change("D 293 250000 00402", "C 103 250000"), ACCOUNTS).getFirst();
        assertThat(sameCategoryNoBatchimSubgroup.message())
                .isEqualTo("293 장기차입금은 이 거래의 계정이 아니에요. 부채인 것은 맞지만 비유동부채가 아니에요.");
    }

    @Test
    @DisplayName("반려 사유는 정답 계정을 알려 주지 않는다")
    void findingsDoNotRevealTheAnswer() {
        assertThat(grade("D 101 12000000", "C 108 12000000 00101").getFirst().message()).doesNotContain("보통예금");
    }

    @Test
    @DisplayName("계정은 맞는데 금액이 다르면 WRONG_AMOUNT — 차액과 크고 작음을 함께 보여 준다")
    void wrongAmountShowsDifference() {
        List<Finding> smaller = grade("D 103 1200000", "C 108 1200000 00101");
        assertThat(smaller).extracting(Finding::type).containsExactly(FindingType.WRONG_AMOUNT);
        assertThat(smaller.getFirst().message()).contains("차액 10,800,000원").contains("더 적게 입력했어요");

        List<Finding> larger = grade("D 103 15000000", "C 108 15000000 00101");
        assertThat(larger).extracting(Finding::type).containsExactly(FindingType.WRONG_AMOUNT);
        assertThat(larger.getFirst().message()).contains("차액 3,000,000원").contains("더 크게 입력했어요");

        // 복합 분개에서 계정마다 차액이 다르면 계정별로 차액을 보여 준다
        Map<Balances.Key, Long> compoundExpected = change("D 153 10000000", "D 135 1000000", "C 251 11000000 00201");
        List<Finding> compoundWrong = Grader.grade(compoundExpected,
                change("D 153 10500000", "D 135 800000", "C 251 11300000 00201"), ACCOUNTS);
        assertThat(compoundWrong).extracting(Finding::type).containsExactly(FindingType.WRONG_AMOUNT);
        assertThat(compoundWrong.getFirst().message())
                .contains("135 부가세대급금 (차액 200,000원)")
                .contains("153 원재료 (차액 500,000원)")
                .contains("251 외상매입금 (차액 300,000원)");
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

    @Test
    @DisplayName("EntryGrader: 오답 예시에 걸리면 MISTAKE로 전용 사유를 먼저 돌려주고, 아니면 범용 진단을 쓴다")
    void entryGraderPrefersMistakeOverGenericDiagnosis() {
        Scenario.Entry answer = new Scenario.Entry(2, LocalDate.of(2026, 1, 15), EntryType.GENERAL, "미지급금 지급",
                List.of(new Scenario.Line(Side.DEBIT, "253", 1_500_000, "00301"),
                        new Scenario.Line(Side.CREDIT, "103", 1_500_000, null)));
        Course.Mistake missingPartnerMistake = new Course.Mistake(
                List.of(new Scenario.Line(Side.DEBIT, "253", 1_500_000, null),
                        new Scenario.Line(Side.CREDIT, "103", 1_500_000, null)),
                "미지급금 거래처가 빠져 있어요.");
        Course.Mistake expenseMistake = new Course.Mistake(
                List.of(new Scenario.Line(Side.DEBIT, "819", 1_500_000, null),
                        new Scenario.Line(Side.CREDIT, "103", 1_500_000, null)),
                "임차료 비용이 아니라 작년 미지급금을 줄여야 해요.");
        Course.Mission mission = new Course.Mission("payable-other", "ch1", Course.MissionKind.ENTRY, "미지급금 지급", "brief",
                List.of("h1", "h2"), List.of(), "explanation", List.of("basis"), "exam_ref", answer,
                List.of(), List.of(missingPartnerMistake, expenseMistake));

        EntryGrader grader = new EntryGrader();

        // 오답 예시 1(거래처 누락) → MISTAKE + 전용 사유
        List<Finding> missingRes = grader.grade(MissionGrader.Context.ofEntry(mission,
                change("D 253 1500000", "C 103 1500000"), ACCOUNTS));
        assertThat(missingRes).containsExactly(new Finding(FindingType.MISTAKE, "미지급금 거래처가 빠져 있어요."));

        // 다른 거래처(00603)를 넣은 경우는 "거래처 누락" 오답 예시에 걸리지 않고 범용 WRONG_PARTNER로 간다
        List<Finding> wrongPartnerRes = grader.grade(MissionGrader.Context.ofEntry(mission,
                change("D 253 1500000 00603", "C 103 1500000"), ACCOUNTS));
        assertThat(wrongPartnerRes).extracting(Finding::type).containsExactly(FindingType.WRONG_PARTNER);

        // 오답 예시 2(임차료 비용) — 보통예금에 거래처를 추가로 붙여도 오답 예시에 걸린다
        List<Finding> expenseRes = grader.grade(MissionGrader.Context.ofEntry(mission,
                change("D 819 1500000", "C 103 1500000 00301"), ACCOUNTS));
        assertThat(expenseRes).containsExactly(new Finding(FindingType.MISTAKE, "임차료 비용이 아니라 작년 미지급금을 줄여야 해요."));

        // 오답 예시에 없는 다른 계정(817) → 범용 WRONG_ACCOUNT
        List<Finding> genericRes = grader.grade(MissionGrader.Context.ofEntry(mission,
                change("D 817 1500000", "C 103 1500000"), ACCOUNTS));
        assertThat(genericRes).extracting(Finding::type).containsExactly(FindingType.WRONG_ACCOUNT);
        assertThat(genericRes.getFirst().message()).contains("부채예요");
    }

    @Test
    @DisplayName("조사 도우미: 받침 유무(한글·숫자)에 따라 은/는, 이/가, 이에요/예요를 고른다")
    void koreanParticlesMatchBatchim() {
        assertThat(Korean.eunNeun("현금")).isEqualTo("현금은");
        assertThat(Korean.eunNeun("임차료")).isEqualTo("임차료는");
        assertThat(Korean.iGa("유형자산")).isEqualTo("유형자산이");
        assertThat(Korean.iGa("유동부채")).isEqualTo("유동부채가");
        assertThat(Korean.ieyoYeyo("자산")).isEqualTo("자산이에요");
        assertThat(Korean.ieyoYeyo("부채")).isEqualTo("부채예요");
        assertThat(Korean.eunNeun("108")).isEqualTo("108은");
        assertThat(Korean.eunNeun("104")).isEqualTo("104는");
    }
}
