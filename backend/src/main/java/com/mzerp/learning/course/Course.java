package com.mzerp.learning.course;

import java.time.LocalDate;
import java.util.List;

import com.mzerp.learning.scenario.Scenario;

/**
 * 과정 한 개: 장 → 미션. 원본은 {@code data/course/*.toml} (ADR-0015).
 * 미션은 시나리오 거래를 가리키고, 날짜와 정답 분개는 그 거래에서 온다.
 */
public record Course(String key, int version, String title, Scenario scenario, List<Chapter> chapters) {

    public record Chapter(String key, String title, List<Mission> missions) {
    }

    /**
     * @param answer 정답 분개 (시나리오 거래)
     * @param accept 정답으로 인정하는 다른 분개들. 없으면 빈 목록
     */
    public record Mission(String key, String chapterKey, String title, String brief, List<String> hints,
                          Scenario.Entry answer, List<List<Scenario.Line>> accept) {

        public LocalDate date() {
            return answer.date();
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
