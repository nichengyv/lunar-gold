package com.capsule.agentkey.acceptance;

import com.capsule.agentkey.config.PendingFileChangePrecheckCallback;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A：Phase 4 出境验收——V16/V17/V18 在真实 PostgreSQL 上的迁移结果。
 *
 * <p>空库执行 classpath 全量迁移（含 V13 前置预检），断言表、列、索引与幂等性；
 * 依赖 DESKTOP_PG_RUNTIME 提供含 pgvector 的运行时，未设置时整类跳过。
 */
@EnabledIfEnvironmentVariable(named = "DESKTOP_PG_RUNTIME", matches = ".+")
class FlywayV18MigrationAcceptanceTest {

    @TempDir
    static Path dataDir;

    private static EmbeddedPostgres postgres;
    private static String jdbcUrl;
    private static Flyway flyway;

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPgSupport.start(dataDir);
        jdbcUrl = EmbeddedPgSupport.jdbcUrl(postgres);
        flyway = Flyway.configure()
                .dataSource(jdbcUrl, EmbeddedPgSupport.USER, EmbeddedPgSupport.USER)
                .locations("classpath:db/migration")
                .callbacks(new PendingFileChangePrecheckCallback())
                .load();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) {
            postgres.close();
            postgres = null;
        }
    }

    @Test
    void 空库全量迁移到V18且结构完整() throws SQLException {
        assertNotNull(flyway.migrate());
        assertEquals("18", flyway.info().current().getVersion().getVersion(), "应迁移到 V18");
        assertEquals(18, flyway.info().applied().length, "V1~V18 共 18 条迁移应全部成功");

        try (Connection connection = DriverManager.getConnection(
                jdbcUrl, EmbeddedPgSupport.USER, EmbeddedPgSupport.USER);
             Statement statement = connection.createStatement()) {

            for (String table : List.of("agent_run", "agent_run_event", "tool_approval")) {
                assertTrue(tableExists(statement, table), "缺少表 " + table);
            }

            assertColumn(statement, "agent_run", "prompt_tokens", "bigint", "NO");
            assertColumn(statement, "agent_run", "completion_tokens", "bigint", "NO");
            assertColumn(statement, "agent_run", "total_tokens", "bigint", "NO");

            try (ResultSet rs = statement.executeQuery(
                    "SELECT indexdef FROM pg_indexes "
                            + "WHERE schemaname = 'public' AND tablename = 'agent_run' "
                            + "AND indexname = 'uk_agent_run_client_request'")) {
                assertTrue(rs.next(), "缺少幂等唯一索引 uk_agent_run_client_request");
                String def = rs.getString(1).toUpperCase();
                assertTrue(def.contains("UNIQUE") && def.contains("CLIENT_REQUEST_ID"),
                        "幂等索引定义不符合预期: " + def);
            }

            try (ResultSet rs = statement.executeQuery(
                    "SELECT indexdef FROM pg_indexes "
                            + "WHERE schemaname = 'public' AND tablename = 'agent_run_event'")) {
                boolean uniqueRunSeq = false;
                while (rs.next()) {
                    String def = rs.getString(1).toUpperCase();
                    if (def.contains("UNIQUE") && def.contains("RUN_ID") && def.contains("SEQ")) {
                        uniqueRunSeq = true;
                    }
                }
                assertTrue(uniqueRunSeq, "缺少 (run_id, seq) 唯一约束");
            }

            try (ResultSet rs = statement.executeQuery(
                    "SELECT indexdef FROM pg_indexes "
                            + "WHERE schemaname = 'public' AND tablename = 'tool_approval' "
                            + "AND indexname = 'idx_tool_approval_state'")) {
                assertTrue(rs.next(), "缺少 tool_approval 的 state 索引");
            }
        }
    }

    @Test
    void 重复migrate为零条且validate通过() {
        flyway.migrate();
        assertEquals(0, flyway.migrate().migrationsExecuted, "重复迁移不应再执行任何脚本");
        assertDoesNotThrow(() -> flyway.validate(), "迁移后 validate 应通过");
    }

    private static boolean tableExists(Statement statement, String table) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = '" + table + "'")) {
            return rs.next();
        }
    }

    private static void assertColumn(Statement statement, String table, String column,
                                     String dataType, String nullable) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = '" + table
                        + "' AND column_name = '" + column + "'")) {
            assertTrue(rs.next(), "缺少列 " + table + "." + column);
            assertEquals(dataType, rs.getString(1), "列类型不符: " + table + "." + column);
            assertEquals(nullable, rs.getString(2), "列可空性不符: " + table + "." + column);
        }
    }
}
