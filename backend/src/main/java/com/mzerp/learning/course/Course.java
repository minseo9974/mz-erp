package com.mzerp.learning.course;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mzerp.learning.scenario.Scenario;

/**
 * 과정 한 개: 장 → 미션. 원본은 {@code data/course/*.toml} (ADR-0015).
 * 미션은 종류({@link MissionKind})를 가지며, 전표 미션은 시나리오 거래를 가리켜 날짜와 정답 분개를 가져온다 (ADR-0017).
 */
public record Course(String key, int version, String title, Scenario scenario, List<Chapter> chapters) {

    /**
     * 미션 종류 (ADR-0017). 과정 파일에 적지 않으면 {@link #ENTRY}다.
     * L1 단계에서 {@code entry}부터 구현하고, {@code correction}·{@code quiz}·{@code lookup}을 차례로 붙인다.
     */
    public enum MissionKind {
        /** 증빙을 보고 전표 작성 → 결재 요청 */
        ENTRY,
        /** 개념 확인 선택형 */
        QUIZ,
        /** 장부·화면에서 값 찾기 */
        LOOKUP,
        /** 잘못 전기된 전표 정정 (역분개 요청 + 올바른 전표) */
        CORRECTION,
        /** 두 자료의 차이를 찾아 해소 */
        RECONCILE,
        /** 마감·신고처럼 순서가 있는 절차 */
        PROCEDURE,
        /** 한 달치를 모아 마감 때 한 번 채점 */
        BATCH;

        public static MissionKind fromToml(String raw) {
            if (raw == null || raw.isBlank()) {
                return ENTRY;
            }
            return switch (raw.strip().toLowerCase(Locale.ROOT)) {
                case "entry" -> ENTRY;
                case "quiz" -> QUIZ;
                case "lookup" -> LOOKUP;
                case "correction" -> CORRECTION;
                case "reconcile" -> RECONCILE;
                case "procedure" -> PROCEDURE;
                case "batch" -> BATCH;
                default -> throw new IllegalArgumentException("모르는 미션 종류입니다: " + raw);
            };
        }
    }

    /**
     * 미션의 흔한 오답 예시 (ADR-0017).
     * 제출한 변화가 이 분개의 변화와 같으면 범용 사유 대신 {@code says}를 먼저 돌려준다.
     *
     * @param lines 틀린 분개 라인 (entry·correction)
     * @param says  이 실수를 했을 때 돌려줄 미션 전용 반려 사유 (정답 계정을 직접 말하지 않는다)
     */
    public record Mistake(List<Scenario.Line> lines, String says) {
    }

    public record Chapter(String key, String title, List<Mission> missions) {
    }

    /**
     * @param kind        미션 종류. 적지 않으면 {@link MissionKind#ENTRY}
     * @param evidence    증빙: 실습자가 전표를 쓰기 전에 보는 자료 (통장 거래내역, 영수증, 어음). 정답은 들어 있지 않다
     * @param explanation 왜 이 분개인가. 미션을 끝낸 뒤에만 보여 준다
     * @param basis       콘텐츠 검증용 근거 목록 (회계기준·세법 조문, 기준 연도, 국가법령정보센터 링크)
     * @param examRef     대조한 전산회계 1급 기출 회차·문항 (없으면 "대조 기출 없음")
     * @param answer      정답 분개 (시나리오 거래). 전표 미션이 아닌 종류는 null일 수 있다
     * @param accept      정답으로 인정하는 다른 분개들. 없으면 빈 목록
     * @param mistakes    흔한 오답 예시와 미션 전용 반려 사유. 없으면 빈 목록
     */
    public record Mission(String key, String chapterKey, MissionKind kind, String title, String brief,
                          List<String> hints, List<Evidence> evidence, String explanation,
                          List<String> basis, String examRef, Scenario.Entry answer,
                          List<List<Scenario.Line>> accept, List<Mistake> mistakes) {

        public LocalDate date() {
            return answer == null ? null : answer.date();
        }
    }

    /**
     * 증빙 한 장. 화면의 문서 컴포넌트가 종류({@code type})에 맞는 모양으로 그린다.
     * <ul>
     *   <li>{@code bank} 통장 거래내역 — account, opening(이전 잔액), rows [[일자, 적요, 출금, 입금, 잔액], …]</li>
     *   <li>{@code slip} 영수증·확인증 — issuer, items [[항목, 내용], …], amount</li>
     *   <li>{@code note} 약속어음 — number, amount, issuer, payee, issueDate, dueDate, place</li>
     * </ul>
     *
     * @param body 종류별 내용. 형식은 {@link CourseCatalog}가 읽을 때 확인한다
     */
    public record Evidence(String type, String title, Map<String, Object> body) {

        /** 통장 거래내역의 한 줄 */
        public record BankRow(String date, String memo, long out, long in, long balance) {
        }

        /** 통장 거래내역이면 그 줄들, 아니면 빈 목록 */
        public List<BankRow> bankRows() {
            if (!"bank".equals(type)) {
                return List.of();
            }
            return ((List<?>) body.get("rows")).stream().map(r -> {
                List<?> row = (List<?>) r;
                return new BankRow(String.valueOf(row.get(0)), String.valueOf(row.get(1)),
                        ((Number) row.get(2)).longValue(), ((Number) row.get(3)).longValue(), ((Number) row.get(4)).longValue());
            }).toList();
        }
    }

    public List<Mission> missions() {
        return chapters.stream().flatMap(c -> c.missions().stream()).toList();
    }

    public Mission mission(String missionKey) {
        return missions().stream().filter(m -> m.key().equals(missionKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("과정 " + key + "에 미션 " + missionKey + "이(가) 없습니다"));
    }
}
