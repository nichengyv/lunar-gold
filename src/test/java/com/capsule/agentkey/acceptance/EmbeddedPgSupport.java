package com.capsule.agentkey.acceptance;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase 4 出境验收共用的内嵌 PostgreSQL 支撑。
 *
 * <p>V3 迁移需要 pgvector，zonky 默认二进制不含该扩展，因此必须由
 * {@code DESKTOP_PG_RUNTIME} 指定 scripts/build-pg-runtime.ps1 产出的运行时。
 */
final class EmbeddedPgSupport {

    static final String USER = "postgres";
    static final String DATABASE = "postgres";

    private EmbeddedPgSupport() {
    }

    static EmbeddedPostgres start(Path dataDir) throws IOException {
        String raw = System.getenv("DESKTOP_PG_RUNTIME");
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("未设置 DESKTOP_PG_RUNTIME，无法启动含 pgvector 的内嵌 PostgreSQL");
        }
        Path runtime = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isRegularFile(runtime)) {
            throw new IllegalStateException("DESKTOP_PG_RUNTIME 指向的文件不存在: " + runtime);
        }
        return EmbeddedPostgres.builder()
                .setDataDirectory(dataDir.toFile())
                .setCleanDataDirectory(true)
                .setServerConfig("listen_addresses", "127.0.0.1")
                .setPgBinaryResolver((system, architecture) -> open(runtime))
                .start();
    }

    static String jdbcUrl(EmbeddedPostgres postgres) {
        return postgres.getJdbcUrl(USER, DATABASE);
    }

    private static InputStream open(Path runtime) throws IOException {
        return Files.newInputStream(runtime);
    }
}
