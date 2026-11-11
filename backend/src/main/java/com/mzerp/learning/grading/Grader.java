package com.mzerp.learning.grading;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 채점기: 장부의 실제 변화가 기대한 변화와 같은지 보고, 다르면 **왜 다른지**를 유형으로 짚는다 (ADR-0013).
 * <p>
 * DB도 스프링도 모르는 순수 계산이다. 입력은 "기대 변화"와 "실제 변화" 두 묶음뿐이라 단위 테스트로 모든 오답 유형을 확인한다.
 * 변화는 계정·거래처별 순액(차변 − 대변)이다. 예: 원천세 250,000 납부 → {254 예수금: +250,000, 103 보통예금: −250,000}
 * <p>
 * 반려 사유는 정답을 알려 주지 않는다. 무엇이 틀렸는지와 어디를 다시 볼지만 말한다.
 */
public final class Grader {

    public enum FindingType {
        /** 전표를 아직 올리지 않았다 */
        NO_ENTRY,
        /** 차변과 대변이 통째로 반대 */
        SIDE_SWAPPED,
        /** 계정과 금액은 맞는데 거래처가 다르다 */
        WRONG_PARTNER,
        /** 한 계정만 다른 계정으로 썼다 */
        WRONG_ACCOUNT,
        /** 계정은 맞는데 금액이 다르다 */
        WRONG_AMOUNT,
        /** 전표 일자가 거래일과 다르다 (서비스가 붙인다) */
        WRONG_DATE,
        /** 위 유형으로 설명되지 않는 차이 */
        UNEXPECTED_CHANGE
    }

    public record Finding(FindingType type, String message) {
    }

    /** 반려 사유를 쓸 때 필요한 계정 정보 */
    public record AccountInfo(String code, String name, String category, String subgroup) {
    }

    private static final Map<String, String> CATEGORY_LABEL = Map.of(
            "ASSET", "자산", "LIABILITY", "부채", "EQUITY", "자본", "REVENUE", "수익", "EXPENSE", "비용");

    private Grader() {
    }

    /** @return 비어 있으면 통과 */
    public static List<Finding> grade(Map<Balances.Key, Long> expected, Map<Balances.Key, Long> actual,
                                      Map<String, AccountInfo> accounts) {
        if (actual.equals(expected)) {
            return List.of();
        }
        if (actual.isEmpty()) {
            return List.of(new Finding(FindingType.NO_ENTRY, "아직 장부에 달라진 것이 없어요. 전표를 작성해 임시 저장한 뒤 결재를 요청하세요."));
        }
        if (Balances.negate(actual).equals(expected)) {
            return List.of(new Finding(FindingType.SIDE_SWAPPED,
                    "계정과 금액은 맞는데 차변과 대변이 서로 바뀌었어요. 무엇이 늘고 무엇이 줄었는지 다시 보세요."));
        }

        Map<String, Long> expectedByAccount = Balances.byAccount(expected);
        Map<String, Long> actualByAccount = Balances.byAccount(actual);
        if (actualByAccount.equals(expectedByAccount)) {
            Set<String> off = new TreeSet<>();
            Balances.minus(actual, expected).keySet().forEach(k -> off.add(k.accountCode()));
            return List.of(new Finding(FindingType.WRONG_PARTNER,
                    "계정과 금액은 맞는데 거래처가 달라요: " + names(off, accounts) + ". 증빙의 상대방을 확인하세요."));
        }

        Map<String, Long> diff = new java.util.TreeMap<>(actualByAccount);
        expectedByAccount.forEach((code, v) -> diff.merge(code, -v, Long::sum));
        diff.values().removeIf(v -> v == 0);

        Finding wrongAccount = wrongAccount(diff, expectedByAccount, accounts);
        if (wrongAccount != null) {
            return List.of(wrongAccount);
        }
        if (actualByAccount.keySet().equals(expectedByAccount.keySet())) {
            return List.of(new Finding(FindingType.WRONG_AMOUNT,
                    "계정은 맞는데 금액이 증빙과 달라요: " + names(diff.keySet(), accounts) + "."));
        }

        List<Finding> findings = new ArrayList<>();
        Set<String> unexpected = new TreeSet<>(actualByAccount.keySet());
        unexpected.removeAll(expectedByAccount.keySet());
        Set<String> untouched = new TreeSet<>(expectedByAccount.keySet());
        untouched.removeAll(actualByAccount.keySet());
        if (!unexpected.isEmpty()) {
            findings.add(new Finding(FindingType.UNEXPECTED_CHANGE,
                    "이 거래와 관계없는 계정이 바뀌었어요: " + names(unexpected, accounts) + "."));
        }
        if (!untouched.isEmpty()) {
            findings.add(new Finding(FindingType.UNEXPECTED_CHANGE,
                    "이 거래로 바뀌어야 할 계정 " + untouched.size() + "개가 아직 그대로예요. 증빙에서 무엇이 늘고 줄었는지 다시 보세요."));
        }
        if (findings.isEmpty()) {
            findings.add(new Finding(FindingType.UNEXPECTED_CHANGE, "장부의 변화가 이 거래와 맞지 않아요. 계정과 금액을 다시 확인하세요."));
        }
        return findings;
    }

    /**
     * 한 계정만 잘못 고른 경우: 계정별 차이가 딱 두 개이고, 하나는 "있어야 하는데 없는 계정"(E),
     * 다른 하나는 "없어야 하는데 있는 계정"(U)이며 금액이 같다.
     */
    private static Finding wrongAccount(Map<String, Long> diff, Map<String, Long> expectedByAccount,
                                        Map<String, AccountInfo> accounts) {
        if (diff.size() != 2) {
            return null;
        }
        String missing = null;
        String used = null;
        for (String code : diff.keySet()) {
            if (expectedByAccount.containsKey(code) && diff.get(code) == -expectedByAccount.get(code)) {
                missing = code;
            } else if (!expectedByAccount.containsKey(code)) {
                used = code;
            }
        }
        if (missing == null || used == null || diff.get(used) != -diff.get(missing)) {
            return null;
        }
        AccountInfo e = accounts.get(missing);
        AccountInfo u = accounts.get(used);
        if (e == null || u == null) {
            return new Finding(FindingType.WRONG_ACCOUNT, "계정 하나가 이 거래와 맞지 않아요: " + used + ".");
        }
        String label = u.code() + " " + u.name();
        if (e.subgroup().equals(u.subgroup())) {
            return new Finding(FindingType.WRONG_ACCOUNT,
                    label + "은(는) 이 거래의 계정이 아니에요. " + u.subgroup() + "인 것은 맞으니 그 안에서 다시 찾아보세요.");
        }
        if (e.category().equals(u.category())) {
            return new Finding(FindingType.WRONG_ACCOUNT,
                    label + "은(는) 이 거래의 계정이 아니에요. " + CATEGORY_LABEL.get(u.category()) + "인 것은 맞지만 "
                            + u.subgroup() + "이(가) 아니에요.");
        }
        return new Finding(FindingType.WRONG_ACCOUNT,
                label + "은(는) " + CATEGORY_LABEL.get(u.category()) + " 계정인데, 이 거래에서 바뀌는 것은 "
                        + CATEGORY_LABEL.get(e.category()) + "이에요.");
    }

    private static String names(Set<String> codes, Map<String, AccountInfo> accounts) {
        return String.join(", ", codes.stream()
                .map(c -> accounts.containsKey(c) ? c + " " + accounts.get(c).name() : c).toList());
    }
}
