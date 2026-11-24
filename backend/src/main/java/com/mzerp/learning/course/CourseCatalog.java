package com.mzerp.learning.course;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mzerp.common.error.ApiException;
import com.mzerp.journal.Side;
import com.mzerp.learning.scenario.Scenario;
import com.mzerp.learning.scenario.ScenarioLoader;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.dataformat.toml.TomlMapper;

/**
 * 과정 콘텐츠를 {@code classpath:course/<키>.toml}에서 읽어 메모리에 둔다 (빌드할 때 data/course에서 복사됨).
 * 읽을 때 콘텐츠가 엔진과 맞는지 확인한다: 미션 키 중복, 모르는 미션 종류, 없는 시나리오 거래, 오답 예시 형식.
 * DB에는 콘텐츠를 넣지 않는다 — 사용자의 진행 상태만 저장한다 (ADR-0015, ADR-0017).
 */
@Component
public class CourseCatalog {

    private static final TomlMapper TOML = new TomlMapper();

    private final Map<String, Course> cache = new ConcurrentHashMap<>();

    public Course get(String key) {
        if (!key.matches("[a-z0-9-]{1,50}")) { // 경로에 들어가는 값이라 형식을 먼저 확인한다
            throw ApiException.notFound("과정 " + key);
        }
        return cache.computeIfAbsent(key, CourseCatalog::load);
    }

    private static Course load(String key) {
        ClassPathResource resource = new ClassPathResource("course/" + key + ".toml");
        if (!resource.exists()) {
            throw ApiException.notFound("과정 " + key);
        }
        try (InputStream in = resource.getInputStream()) {
            return parse(TOML.readValue(in, Map.class));
        } catch (IOException e) {
            throw new UncheckedIOException("과정 " + key + "을(를) 읽을 수 없습니다", e);
        }
    }

    /** 테스트에서 TOML 문자열의 형식 검증을 직접 확인하기 위한 도우미 */
    static Course parseToml(String toml) {
        return parse(TOML.readValue(toml, Map.class));
    }

    static Course parse(Map<?, ?> root) {
        Scenario scenario = ScenarioLoader.load(text(root, "scenario"));
        Set<String> seen = new HashSet<>();
        List<Course.Chapter> chapters = list(root.get("chapters")).stream().map(c -> {
            Map<?, ?> chapter = (Map<?, ?>) c;
            String chapterKey = text(chapter, "key");
            List<Course.Mission> missions = list(chapter.get("missions")).stream().map(m -> {
                Map<?, ?> mission = (Map<?, ?>) m;
                String missionKey = text(mission, "key");
                if (!seen.add(missionKey)) {
                    throw new IllegalArgumentException("미션 키가 겹칩니다: " + missionKey);
                }
                Course.MissionKind kind = parseKind(mission.get("kind"), missionKey);
                Scenario.Entry answer = parseAnswer(mission.get("scenario"), kind, scenario, missionKey);
                List<String> basis = parseStringList(mission.get("basis"), "미션 " + missionKey + " basis");
                String examRef = optionalText(mission.get("exam_ref")).strip();
                List<Course.Mistake> mistakes = list(mission.get("mistakes")).stream()
                        .map(raw -> parseMistake(raw, kind, missionKey))
                        .toList();
                return new Course.Mission(missionKey, chapterKey, kind, text(mission, "title"), text(mission, "brief"),
                        list(mission.get("hints")).stream().map(String::valueOf).toList(),
                        list(mission.get("evidence")).stream().map(e -> evidence((Map<?, ?>) e, missionKey)).toList(),
                        text(mission, "explanation").strip(),
                        basis, examRef, answer,
                        list(mission.get("accept")).stream()
                                .map(alt -> ScenarioLoader.lines(alt, "미션 " + missionKey + " accept")).toList(),
                        mistakes);
            }).toList();
            return new Course.Chapter(chapterKey, text(chapter, "title"), missions);
        }).toList();
        return new Course(text(root, "key"), ((Number) root.get("version")).intValue(), text(root, "title"), scenario, chapters);
    }

    private static Course.MissionKind parseKind(Object raw, String missionKey) {
        if (raw == null) {
            return Course.MissionKind.ENTRY;
        }
        try {
            return Course.MissionKind.fromToml(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("미션 " + missionKey + ": " + e.getMessage(), e);
        }
    }

    private static Scenario.Entry parseAnswer(Object rawScenario, Course.MissionKind kind, Scenario scenario, String missionKey) {
        if (rawScenario == null) {
            if (kind == Course.MissionKind.ENTRY || kind == Course.MissionKind.CORRECTION) {
                throw new IllegalArgumentException("미션 " + missionKey + "(" + kind + "): scenario 번호가 필요합니다");
            }
            return null;
        }
        if (!(rawScenario instanceof Number number)) {
            throw new IllegalArgumentException("미션 " + missionKey + ": scenario는 거래 번호(정수)여야 합니다 — " + rawScenario);
        }
        return scenario.transaction(number.intValue());
    }

    private static Course.Mistake parseMistake(Object raw, Course.MissionKind kind, String missionKey) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("미션 " + missionKey + " mistakes: 항목은 테이블이어야 합니다 — " + raw);
        }
        String says = text(map, "says").strip();
        if (says.isEmpty()) {
            throw new IllegalArgumentException("미션 " + missionKey + " mistakes: says가 비어 있습니다");
        }
        List<Scenario.Line> lines = map.containsKey("lines")
                ? ScenarioLoader.lines(map.get("lines"), "미션 " + missionKey + " mistakes")
                : List.of();
        if (kind == Course.MissionKind.ENTRY || kind == Course.MissionKind.CORRECTION) {
            if (lines.size() < 2) {
                throw new IllegalArgumentException("미션 " + missionKey + " mistakes: lines는 2줄 이상이어야 합니다");
            }
            if (lines.stream().anyMatch(l -> l.amount() <= 0)) {
                throw new IllegalArgumentException("미션 " + missionKey + " mistakes: 라인 금액은 1원 이상이어야 합니다");
            }
            long debit = lines.stream().filter(l -> l.side() == Side.DEBIT).mapToLong(Scenario.Line::amount).sum();
            long credit = lines.stream().filter(l -> l.side() == Side.CREDIT).mapToLong(Scenario.Line::amount).sum();
            if (debit <= 0 || debit != credit) {
                throw new IllegalArgumentException("미션 " + missionKey + " mistakes: 차변 합계(" + debit + ")와 대변 합계(" + credit + ")가 같고 0보다 커야 합니다");
            }
        }
        return new Course.Mistake(lines, says);
    }

    private static List<String> parseStringList(Object raw, String where) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> items)) {
            throw new IllegalArgumentException(where + ": 목록 형식이어야 합니다 — " + raw);
        }
        return items.stream().map(v -> String.valueOf(v).strip()).toList();
    }

    private static String optionalText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 증빙의 종류별 필수 항목. 빠지면 화면이 빈 문서를 그리게 되므로 읽을 때 막는다 */
    private static final Map<String, List<String>> EVIDENCE_FIELDS = Map.of(
            "bank", List.of("account", "opening", "rows"),
            "slip", List.of("issuer", "items", "amount"),
            "note", List.of("number", "amount", "issuer", "payee", "issueDate", "dueDate", "place"));

    private static Course.Evidence evidence(Map<?, ?> raw, String missionKey) {
        String type = text(raw, "type");
        List<String> required = EVIDENCE_FIELDS.get(type);
        if (required == null) {
            throw new IllegalArgumentException("미션 " + missionKey + ": 모르는 증빙 종류 " + type);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (!"type".equals(k) && !"title".equals(k)) {
                body.put(String.valueOf(k), v);
            }
        });
        for (String field : required) {
            if (!body.containsKey(field)) {
                throw new IllegalArgumentException("미션 " + missionKey + " 증빙(" + type + ")에 " + field + " 값이 없습니다");
            }
        }
        Course.Evidence evidence = new Course.Evidence(type, text(raw, "title"), body);
        evidence.bankRows(); // 통장 줄의 모양(일자, 적요, 출금, 입금, 잔액)이 틀리면 여기서 드러난다
        return evidence;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static String text(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("과정 파일에 " + key + " 값이 없습니다 — " + m.keySet());
        }
        return String.valueOf(v);
    }
}
