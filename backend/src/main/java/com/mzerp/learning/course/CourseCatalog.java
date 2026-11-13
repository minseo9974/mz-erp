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
import com.mzerp.learning.scenario.Scenario;
import com.mzerp.learning.scenario.ScenarioLoader;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.dataformat.toml.TomlMapper;

/**
 * 과정 콘텐츠를 {@code classpath:course/<키>.toml}에서 읽어 메모리에 둔다 (빌드할 때 data/course에서 복사됨).
 * 읽을 때 콘텐츠가 엔진과 맞는지 확인한다: 미션 키 중복, 없는 시나리오 거래.
 * DB에는 콘텐츠를 넣지 않는다 — 사용자의 진행 상태만 저장한다 (ADR-0015).
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

    private static Course parse(Map<?, ?> root) {
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
                Scenario.Entry answer = scenario.transaction(((Number) mission.get("scenario")).intValue());
                return new Course.Mission(missionKey, chapterKey, text(mission, "title"), text(mission, "brief"),
                        list(mission.get("hints")).stream().map(String::valueOf).toList(),
                        list(mission.get("evidence")).stream().map(e -> evidence((Map<?, ?>) e, missionKey)).toList(),
                        text(mission, "explanation").strip(), answer,
                        list(mission.get("accept")).stream()
                                .map(alt -> ScenarioLoader.lines(alt, "미션 " + missionKey + " accept")).toList());
            }).toList();
            return new Course.Chapter(chapterKey, text(chapter, "title"), missions);
        }).toList();
        return new Course(text(root, "key"), ((Number) root.get("version")).intValue(), text(root, "title"), scenario, chapters);
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
