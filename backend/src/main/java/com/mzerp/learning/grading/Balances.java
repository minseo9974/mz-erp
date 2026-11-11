package com.mzerp.learning.grading;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.mzerp.journal.Side;

/**
 * 계정·거래처별 순잔액(차변 − 대변)의 묶음과, 두 묶음의 차이를 구하는 계산.
 * 채점은 "미션 시작 때의 잔액"과 "지금 잔액"의 차이를 정답 분개가 만드는 차이와 비교한다 (ADR-0013).
 */
public final class Balances {

    /** @param partnerCode 거래처가 없으면 null */
    public record Key(String accountCode, String partnerCode) implements Comparable<Key> {
        @Override
        public int compareTo(Key o) {
            int byAccount = accountCode.compareTo(o.accountCode);
            if (byAccount != 0) {
                return byAccount;
            }
            return (partnerCode == null ? "" : partnerCode).compareTo(o.partnerCode == null ? "" : o.partnerCode);
        }

        /** "108|00101" 또는 "101|" — 시작 잔액을 JSON으로 저장할 때의 키 */
        public String encode() {
            return accountCode + "|" + (partnerCode == null ? "" : partnerCode);
        }

        public static Key decode(String text) {
            int bar = text.indexOf('|');
            String partner = text.substring(bar + 1);
            return new Key(text.substring(0, bar), partner.isEmpty() ? null : partner);
        }
    }

    private Balances() {
    }

    /** 라인 하나를 더한다: 차변이면 +, 대변이면 −. 0이 된 항목은 지운다. */
    public static void add(Map<Key, Long> net, String accountCode, String partnerCode, Side side, long amount) {
        long signed = side == Side.DEBIT ? amount : -amount;
        net.merge(new Key(accountCode, partnerCode), signed, Long::sum);
        net.values().removeIf(v -> v == 0);
    }

    /** a − b. 변화가 없는 항목은 결과에 없다. */
    public static Map<Key, Long> minus(Map<Key, Long> a, Map<Key, Long> b) {
        Map<Key, Long> out = new TreeMap<>(a);
        b.forEach((k, v) -> out.merge(k, -v, Long::sum));
        out.values().removeIf(v -> v == 0);
        return out;
    }

    public static Map<Key, Long> negate(Map<Key, Long> a) {
        Map<Key, Long> out = new TreeMap<>();
        a.forEach((k, v) -> out.put(k, -v));
        return out;
    }

    /** 거래처를 무시하고 계정별로 합친다. */
    public static Map<String, Long> byAccount(Map<Key, Long> a) {
        Map<String, Long> out = new TreeMap<>();
        a.forEach((k, v) -> out.merge(k.accountCode(), v, Long::sum));
        out.values().removeIf(v -> v == 0);
        return out;
    }

    public static Map<String, Long> encode(Map<Key, Long> a) {
        Map<String, Long> out = new TreeMap<>();
        a.forEach((k, v) -> out.put(k.encode(), v));
        return out;
    }

    public static Map<Key, Long> decode(Map<String, Long> a) {
        Map<Key, Long> out = new HashMap<>();
        a.forEach((k, v) -> out.put(Key.decode(k), v));
        return out;
    }
}
