package com.mzerp.learning.grading;

/**
 * 한국어 받침에 맞춰 조사를 고르는 도우미.
 * 반려 사유에서 "부채이에요", "현금은(는)" 같은 어색한 문장을 없앤다.
 */
final class Korean {

    private Korean() {
    }

    static String eunNeun(String word) {
        return word + (hasBatchim(word) ? "은" : "는");
    }

    static String iGa(String word) {
        return word + (hasBatchim(word) ? "이" : "가");
    }

    static String ieyoYeyo(String word) {
        return word + (hasBatchim(word) ? "이에요" : "예요");
    }

    /** 끝 글자에 받침이 있는가 (공백·닫는 괄호 등은 건너뛰고 마지막 글자를 본다). */
    static boolean hasBatchim(String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        int i = word.length() - 1;
        while (i >= 0 && isIgnorableTrailing(word.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        char ch = word.charAt(i);
        if (ch >= '가' && ch <= '힣') {
            return (ch - 0xAC00) % 28 != 0;
        }
        if (ch >= '0' && ch <= '9') {
            // 0(영), 1(일), 3(삼), 6(육), 7(칠), 8(팔)은 받침 있음 / 2(이), 4(사), 5(오), 9(구)는 없음
            return ch == '0' || ch == '1' || ch == '3' || ch == '6' || ch == '7' || ch == '8';
        }
        return false;
    }

    private static boolean isIgnorableTrailing(char ch) {
        return Character.isWhitespace(ch) || ch == ')' || ch == ']' || ch == '"' || ch == '\'';
    }
}
