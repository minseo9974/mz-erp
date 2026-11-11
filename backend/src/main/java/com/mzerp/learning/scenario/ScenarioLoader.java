package com.mzerp.learning.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.mzerp.journal.EntryType;
import com.mzerp.journal.Side;
import com.mzerp.master.partner.PartnerType;

import org.springframework.core.io.ClassPathResource;

import tools.jackson.dataformat.toml.TomlMapper;

/**
 * {@code classpath:scenario/<이름>.toml}을 {@link Scenario}로 읽는다 (빌드할 때 data/scenario에서 복사됨).
 * <p>
 * 라인은 TOML에서 {@code ["D", "153", 10000000, "00201"]}처럼 타입이 섞인 배열이라 클래스에 바로 묶지 않고
 * Map·List로 읽은 뒤 손으로 옮긴다. 형식이 틀리면 어느 전표의 무엇이 틀렸는지 알려 주고 멈춘다.
 */
public final class ScenarioLoader {

    private static final TomlMapper TOML = new TomlMapper();

    private ScenarioLoader() {
    }

    public static Scenario load(String name) {
        try (InputStream in = new ClassPathResource("scenario/" + name + ".toml").getInputStream()) {
            return parse(TOML.readValue(in, Map.class));
        } catch (IOException e) {
            throw new UncheckedIOException("시나리오 " + name + "을(를) 읽을 수 없습니다", e);
        }
    }

    private static Scenario parse(Map<?, ?> root) {
        Map<?, ?> company = map(root.get("company"), "company");
        Map<?, ?> opening = map(root.get("opening"), "opening");
        return new Scenario(
                string(company, "name"),
                ((Number) company.get("term")).intValue(),
                date(company.get("start")),
                date(company.get("end")),
                list(root.get("partners")).stream().map(p -> partner(map(p, "partners"))).toList(),
                new Scenario.Entry(0, date(opening.get("date")), EntryType.OPENING, string(opening, "desc"),
                        lines(opening.get("lines"), "기초잔액")),
                list(root.get("entries")).stream().map(e -> entry(map(e, "entries"), null)).toList(),
                list(root.get("closing")).stream().map(e -> entry(map(e, "closing"), EntryType.CLOSING)).toList());
    }

    private static Scenario.PartnerSeed partner(Map<?, ?> p) {
        return new Scenario.PartnerSeed(string(p, "code"), string(p, "name"), PartnerType.valueOf(string(p, "type")));
    }

    /** @param fixedType 결산정리처럼 파일에 type이 없는 구역의 유형. null이면 전표의 type을 읽는다 */
    private static Scenario.Entry entry(Map<?, ?> e, EntryType fixedType) {
        int no = ((Number) e.get("no")).intValue();
        EntryType type = fixedType != null ? fixedType : EntryType.valueOf(string(e, "type"));
        return new Scenario.Entry(no, date(e.get("date")), type, string(e, "desc"),
                lines(e.get("lines"), type + " #" + no));
    }

    /** 라인 배열 한 묶음을 읽는다. 과정 파일의 accept(대안 분개)도 같은 형식이라 함께 쓴다. */
    public static List<Scenario.Line> lines(Object raw, String where) {
        return list(raw).stream().map(item -> {
            List<?> cells = list(item);
            if (cells.size() < 3 || cells.size() > 4) {
                throw new IllegalArgumentException(where + ": 라인은 [차대, 계정, 금액, (거래처)] 형식이어야 합니다 — " + cells);
            }
            Side side = switch (String.valueOf(cells.get(0))) {
                case "D" -> Side.DEBIT;
                case "C" -> Side.CREDIT;
                default -> throw new IllegalArgumentException(where + ": 차대는 D 또는 C — " + cells);
            };
            return new Scenario.Line(side, String.valueOf(cells.get(1)), ((Number) cells.get(2)).longValue(),
                    cells.size() == 4 ? String.valueOf(cells.get(3)) : null);
        }).toList();
    }

    private static Map<?, ?> map(Object value, String name) {
        if (value instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException("시나리오 파일에 [" + name + "] 구역이 없거나 형식이 다릅니다");
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static String string(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("시나리오 파일에 " + key + " 값이 없습니다 — " + m);
        }
        return String.valueOf(v);
    }

    /** TOML 날짜는 설정에 따라 문자열 또는 LocalDate로 온다. 어느 쪽이든 받는다. */
    private static LocalDate date(Object value) {
        return value instanceof LocalDate d ? d : LocalDate.parse(String.valueOf(value));
    }
}
