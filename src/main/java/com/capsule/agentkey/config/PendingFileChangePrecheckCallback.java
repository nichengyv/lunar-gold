package com.capsule.agentkey.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 全新数据库的前置兼容：V13 先对 pending_file_change 执行 ALTER，而该表直到 V14 才创建，
 * 因此空库跑到 V13 必然失败（存量库走过 dev 的 ddl-auto=update，所以一直没暴露）。
 *
 * 不能改已执行过的 V13/V14，否则会破坏存量库的 checksum；这里在 migrate 前做一次幂等预检：
 * 表不存在就先按 V14 的最终结构建好，V13 的 ADD COLUMN IF NOT EXISTS 与 V14 的 CREATE TABLE IF NOT EXISTS
 * 随后都变成空操作，存量库则完全不受影响。
 */
@Slf4j
@Component
public class PendingFileChangePrecheckCallback implements Callback {

    private static final String TABLE_NAME = "pending_file_change";

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS pending_file_change (
                id              BIGSERIAL PRIMARY KEY,
                project_id      BIGINT NOT NULL,
                session_id      BIGINT,
                turn_id         VARCHAR(64),
                user_id         BIGINT NOT NULL,
                file_path       VARCHAR(500) NOT NULL,
                action          VARCHAR(20) NOT NULL,
                source          VARCHAR(60) NOT NULL,
                old_content     TEXT,
                new_content     TEXT,
                diff_text       TEXT,
                additions       INTEGER,
                deletions       INTEGER,
                status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                create_time     TIMESTAMP,
                update_time     TIMESTAMP
            )
            """;

    private static final String CREATE_INDEXES_SQL = """
            CREATE INDEX IF NOT EXISTS idx_pfc_project ON pending_file_change (project_id);
            CREATE INDEX IF NOT EXISTS idx_pfc_session ON pending_file_change (session_id);
            CREATE INDEX IF NOT EXISTS idx_pfc_turn ON pending_file_change (session_id, turn_id);
            CREATE INDEX IF NOT EXISTS idx_pfc_status ON pending_file_change (status)
            """;

    @Override
    public boolean supports(Event event, Context context) {
        return Event.BEFORE_MIGRATE.equals(event);
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        Connection connection = context.getConnection();
        if (connection == null) {
            log.warn("Flyway 预检未拿到连接，跳过 pending_file_change 前置检查");
            return;
        }
        try {
            if (tableExists(connection)) {
                return;
            }
            log.warn("检测到全新数据库缺少 {}，按 V14 结构预建，避免 V13 的 ALTER 失败", TABLE_NAME);
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_TABLE_SQL);
                statement.execute(CREATE_INDEXES_SQL);
            }
        } catch (SQLException e) {
            throw new FlywayException("pending_file_change 前置预检失败: " + e.getMessage(), e);
        }
    }

    private boolean tableExists(Connection connection) throws SQLException {
        // 用 DatabaseMetaData 而不是 information_schema，保持与数据库方言无关；
        // PostgreSQL 会把未加引号的标识符存成小写，这里统一用表名原文
        try (ResultSet tables = connection.getMetaData()
                .getTables(null, null, TABLE_NAME, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    @Override
    public String getCallbackName() {
        return "pendingFileChangePrecheck";
    }
}
