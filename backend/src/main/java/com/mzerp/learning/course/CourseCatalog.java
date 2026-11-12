package com.mzerp.learning.course;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
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
                        text(mission, "explanation").strip(), answer,
                        list(mission.get("accept")).stream()
                                .map(alt -> ScenarioLoader.lines(alt, "미션 " + missionKey + " accept")).toList());
            }).toList();
            return new Course.Chapter(chapterKey, text(chapter, "title"), missions);
        }).toList();
        return new Course(text(root, "key"), ((Number) root.get("version")).intValue(), text(root, "title"), scenario, chapters);
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
