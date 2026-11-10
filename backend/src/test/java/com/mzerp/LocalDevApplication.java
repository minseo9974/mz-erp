package com.mzerp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;

/**
 * 로컬 개발용 실행기 — PostgreSQL 설치 없이 백엔드를 띄운다.
 * <pre>cd backend && ./gradlew bootTestRun</pre>
 * <ul>
 *   <li>임베디드 PostgreSQL 16을 포트 54329에 띄우고, 데이터는 backend/.local/pgdata 에 남긴다 (재시작해도 유지)</li>
 *   <li>처음 실행하면 개발용 관리자 admin / admin1234 를 만든다 (운영과 무관, 이 클래스는 테스트 소스라 jar에 안 들어감)</li>
 *   <li>데이터를 초기화하려면 backend/.local 폴더를 지운다</li>
 * </ul>
 */
public final class LocalDevApplication {

    private LocalDevApplication() {
    }

    public static void main(String[] args) throws IOException {
        Path dataDir = Path.of(".local", "pgdata").toAbsolutePath();
        Files.createDirectories(dataDir);
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setPort(54329)
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(false)
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (IOException ignored) {
                // 종료 중이므로 무시
            }
        }));

        System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "");
        System.setProperty("mzerp.bootstrap.admin-login-id", "admin");
        System.setProperty("mzerp.bootstrap.admin-password", "admin1234");
        System.setProperty("mzerp.bootstrap.admin-name", "개발 관리자");

        SpringApplication.run(BackendApplication.class, args);
    }
}
