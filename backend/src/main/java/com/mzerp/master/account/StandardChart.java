package com.mzerp.master.account;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;

/**
 * 표준 계정과목표. 원본은 저장소의 data/seed/accounts.csv이고, 빌드 시 classpath:seed/accounts.csv로 복사된다.
 * 새 회사를 만들 때 이 표를 그대로 복사한다.
 */
public final class StandardChart {

    static final String RESOURCE = "seed/accounts.csv";
    private static final String HEADER =
            "code,name,category,subgroup,normal_balance,contra_of,requires_partner,statement,is_system";

    public record Row(String code, String name, AccountCategory category, String subgroup, NormalBalance normalBalance,
                      String contraOf, boolean requiresPartner, StatementType statement, boolean system) {
    }

    private StandardChart() {
    }

    public static List<Row> load() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            return parse(reader.lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException("표준 계정과목표를 읽을 수 없습니다: " + RESOURCE, e);
        }
    }

    static List<Row> parse(List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).strip().replace("﻿", "").equals(HEADER)) {
            throw new IllegalStateException("accounts.csv 헤더가 예상과 다릅니다: " + (lines.isEmpty() ? "(빈 파일)" : lines.get(0)));
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(",", -1);
            if (f.length != 9) {
                throw new IllegalStateException("accounts.csv " + (i + 1) + "행: 칸 수가 9가 아닙니다 → " + line);
            }
            rows.add(new Row(f[0], f[1], AccountCategory.valueOf(f[2]), f[3], NormalBalance.valueOf(f[4]),
                    f[5].isEmpty() ? null : f[5], Boolean.parseBoolean(f[6]), StatementType.valueOf(f[7]),
                    Boolean.parseBoolean(f[8])));
        }
        return rows;
    }
}
