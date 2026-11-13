package com.mzerp.learning.course;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.mzerp.learning.scenario.Scenario;

/**
 * 과정 한 개: 장 → 미션. 원본은 {@code data/course/*.toml} (ADR-0015).
 * 미션은 시나리오 거래를 가리키고, 날짜와 정답 분개는 그 거래에서 온다.
 */
public record Course(String key, int version, String title, Scenario scenario, List<Chapter> chapters) {

    public record Chapter(String key, String title, List<Mission> missions) {
    }

    /**
     * @param evidence    증빙: 실습자가 전표를 쓰기 전에 보는 자료 (통장 거래내역, 영수증, 어음). 정답은 들어 있지 않다
     * @param explanation 왜 이 분개인가. 미션을 끝낸 뒤에만 보여 준다
     * @param answer      정답 분개 (시나리오 거래)
     * @param accept      정답으로 인정하는 다른 분개들. 없으면 빈 목록
     */
    public record Mission(String key, String chapterKey, String title, String brief, List<String> hints,
                          List<Evidence> evidence, String explanation, Scenario.Entry answer,
                          List<List<Scenario.Line>> accept) {

        public LocalDate date() {
            return answer.date();
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
