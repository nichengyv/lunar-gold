package com.capsule.agentkey.service.desktop;

import com.capsule.agentkey.config.PendingFileChangePrecheckCallback;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桌面端真实数据库集成测试：内嵌 PostgreSQL + pgvector 运行时（P2-08）。
 *
 * 依赖由 scripts/build-pg-runtime.ps1 产出的运行时压缩包，用 DESKTOP_PG_RUNTIME 指定；
 * 未设置该环境变量时整类跳过，普通 mvn test 不要求本机具备运行时。
 *
 * 覆盖：V1~V15 在空库上完整执行、pg_extension 存在 vector、vector_record 列类型为 vector(1024)、
 * 1024 维写入与余弦检索排序、维度不符写入被拒绝。
 */
@EnabledIfEnvironmentVariable(named = "DESKTOP_PG_RUNTIME", matches = ".+")
class DesktopPgVectorIntegrationTest {

    private static final String USER = DbLifecycleManager.USER;
    private static final String DATABASE = "postgres";
    private static final int DIMENSION = 1024;

    @TempDir
    static Path dataDir;

    private static EmbeddedPostgres postgres;
    private static String jdbcUrl;

    @BeforeAll
    static void startDatabase() throws Exception {
        Path runtime = Path.of(System.getenv("DESKTOP_PG_RUNTIME")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(runtime), "DESKTOP_PG_RUNTIME 指向的文件不存在: " + runtime);

        postgres = EmbeddedPostgres.builder()
                .setDataDirectory(dataDir.toFile())
                .setCleanDataDirectory(true)
                .setServerConfig("listen_addresses", "127.0.0.1")
                .setPgBinaryResolver((system, architecture) -> open(runtime))
                .start();
        jdbcUrl = postgres.getJdbcUrl(USER, DATABASE);

        // 与生产一致：走真实的 classpath 迁移脚本，并挂上全新库的 V13 前置预检
        Flyway.configure()
                .dataSource(jdbcUrl, USER, USER)
                .locations("classpath:db/migration")
                .callbacks(new PendingFileChangePrecheckCallback())
                .load()
                .migrate();
    }

    private static InputStream open(Path runtime) throws IOException {
        return Files.newInputStream(runtime);
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) {
            postgres.close();
            postgres = null;
        }
    }

    @Test
    void 空库完成V1到V15迁移且启用pgvector() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, USER, USER);
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT extversion FROM pg_extension WHERE extname = 'vector'")) {
                assertTrue(rs.next(), "pg_extension 中应存在 vector 扩展");
                assertTrue(rs.getString(1).startsWith("0.8"), "pgvector 版本应为 0.8.x，实际 " + rs.getString(1));
            }
            // version 是字符串列，直接 max() 会得到 '9'；按安装顺序取最后一条
            try (ResultSet rs = statement.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE success "
                            + "ORDER BY installed_rank DESC LIMIT 1")) {
                assertTrue(rs.next());
                assertEquals("15", rs.getString(1));
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT udt_name FROM information_schema.columns "
                            + "WHERE table_name = 'vector_record' AND column_name = 'embedding'")) {
                assertTrue(rs.next());
                assertEquals("vector", rs.getString(1));
            }
        }
    }

    @Test
    void 一千零二十四维向量按余弦距离排序() throws SQLException {
        String first = oneHot(0);
        String second = oneHot(1);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, USER, USER);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM vector_record WHERE user_id = 900001");
            insert(statement, 900001, "alpha", first);
            insert(statement, 900001, "beta", second);

            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT content, 1 - (embedding <=> ?::vector) AS score "
                            + "FROM vector_record WHERE user_id = 900001 "
                            + "ORDER BY embedding <=> ?::vector LIMIT 2")) {
                query.setString(1, first);
                query.setString(2, first);
                try (ResultSet rs = query.executeQuery()) {
                    List<String> contents = new ArrayList<>();
                    List<Double> scores = new ArrayList<>();
                    while (rs.next()) {
                        contents.add(rs.getString(1));
                        scores.add(rs.getDouble(2));
                    }
                    assertEquals(List.of("alpha", "beta"), contents);
                    assertEquals(1.0, scores.get(0), 0.0001);
                    assertEquals(0.0, scores.get(1), 0.0001);
                }
            } finally {
                statement.executeUpdate("DELETE FROM vector_record WHERE user_id = 900001");
            }
        }
    }

    @Test
    void 维度不符的向量被拒绝写入() throws SQLException {
        String tooShort = oneHot(0, DIMENSION - 1);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, USER, USER);
             Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> insert(statement, 900002, "broken", tooShort));
        }
    }

    private static void insert(Statement statement, long userId, String content, String embedding)
            throws SQLException {
        statement.executeUpdate("INSERT INTO vector_record(user_id, document_id, content, source_type, embedding) "
                + "VALUES (" + userId + ", 1, '" + content + "', 'knowledge', '" + embedding + "'::vector)");
    }

    private static String oneHot(int index) {
        return oneHot(index, DIMENSION);
    }

    /** 生成指定维度的 one-hot 向量字面量，用于构造确定的余弦相似度 */
    private static String oneHot(int index, int dimension) {
        StringBuilder builder = new StringBuilder(dimension * 2 + 2).append('[');
        for (int i = 0; i < dimension; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(i == index ? '1' : '0');
        }
        return builder.append(']').toString();
    }
}
