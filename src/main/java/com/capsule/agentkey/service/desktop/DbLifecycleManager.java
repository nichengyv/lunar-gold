package com.capsule.agentkey.service.desktop;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

/**
 * 桌面端本地数据库生命周期：数据目录、首次初始化、空闲端口、启动、健康检查、优雅关闭、崩溃恢复。
 *
 * 仅在 desktop profile 下装配。数据库为随包分发的 PostgreSQL + pgvector 运行时，
 * 数据目录固定在本机（默认 ~/.agentkey/desktop/db），退出后数据保留；
 * 云端仍走外部 PostgreSQL，不经过本类。
 */
@Slf4j
@Component
@Profile("desktop")
public class DbLifecycleManager {

    /** zonky 在本地以 trust 认证启动，用户名固定为 postgres，业务库沿用默认 postgres */
    public static final String USER = "postgres";
    static final String DATABASE = "postgres";

    private static final String PID_FILE = "postmaster.pid";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);

    private final Path dataDir;
    private final Path pgRuntime;
    private final int configuredPort;

    private EmbeddedPostgres postgres;

    public DbLifecycleManager(
            @Value("${app.desktop.data-dir:${user.home}/.agentkey/desktop}") String dataDir,
            @Value("${app.desktop.pg-runtime:}") String pgRuntime,
            @Value("${app.desktop.db-port:0}") int dbPort) {
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize().resolve("db");
        this.pgRuntime = StringUtils.hasText(pgRuntime)
                ? Path.of(pgRuntime).toAbsolutePath().normalize()
                : null;
        this.configuredPort = dbPort;
    }

    @PostConstruct
    public synchronized void start() {
        if (postgres != null) {
            return;
        }
        try {
            Files.createDirectories(dataDir);
            recoverStaleLock(dataDir);
            int port = configuredPort > 0 ? configuredPort : findFreePort();
            postgres = builder(port).start();
            log.info("本地数据库已启动 [port={}, dataDir={}]", port, dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("本地数据库启动失败: " + e.getMessage(), e);
        }
    }

    private EmbeddedPostgres.Builder builder(int port) {
        EmbeddedPostgres.Builder builder = EmbeddedPostgres.builder()
                .setDataDirectory(dataDir.toFile())
                // 保留数据目录：重启复用数据，而不是每次重新 initdb
                .setCleanDataDirectory(false)
                .setPort(port)
                .setPGStartupWait(STARTUP_TIMEOUT)
                // 桌面端只服务本机，不监听外部网卡
                .setServerConfig("listen_addresses", "127.0.0.1")
                .setServerConfig("max_connections", "20");
        if (pgRuntime != null) {
            if (!Files.isRegularFile(pgRuntime)) {
                throw new IllegalStateException(
                        "DESKTOP_PG_RUNTIME 指向的文件不存在: " + pgRuntime
                                + "（需要包含 pgvector 的 PostgreSQL 运行时压缩包）");
            }
            builder.setPgBinaryResolver(this::openRuntime);
        } else {
            // 依赖内置二进制不含 pgvector，V3 迁移会失败；开发期要么配 DESKTOP_PG_RUNTIME，
            // 要么确认自定义打包已替换内置运行时
            log.warn("未配置 DESKTOP_PG_RUNTIME，使用依赖内置的 PostgreSQL 二进制，pgvector 扩展可能不可用");
        }
        return builder;
    }

    /** zonky 需要的是压缩包输入流，每次调用返回新的流，避免第二次启动读到已关闭的流 */
    private InputStream openRuntime(String system, String architecture) throws IOException {
        log.info("加载桌面端 PostgreSQL 运行时 [system={}, arch={}, path={}]", system, architecture, pgRuntime);
        return Files.newInputStream(pgRuntime);
    }

    /**
     * 崩溃恢复：上一次进程异常退出时 postmaster.pid 会残留，PG 会拒绝启动。
     * 记录中的 PID 已不存在 → 清理残留；仍然存活 → 说明另一个实例正占用同一数据目录，直接报错。
     *
     * @return 是否清理了残留文件
     */
    static boolean recoverStaleLock(Path dbDir) throws IOException {
        Path pidFile = dbDir.resolve(PID_FILE);
        if (!Files.exists(pidFile)) {
            return false;
        }
        List<String> lines = Files.readAllLines(pidFile, StandardCharsets.UTF_8);
        long pid = parsePid(lines);
        if (pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            throw new IllegalStateException(
                    "本地数据库已被其他进程占用 [pid=" + pid + ", dataDir=" + dbDir + "]，请先退出另一个实例");
        }
        Files.deleteIfExists(pidFile);
        log.warn("检测到上次异常退出残留的 {}，已清理并可重新启动 [pid={}]", PID_FILE, pid);
        return true;
    }

    private static long parsePid(List<String> lines) {
        if (lines.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(lines.get(0).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int findFreePort() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("没有可用的本地端口", e);
        }
    }

    /** 健康检查：用业务账号实连一次；启动后供日志与后续 /health 复用 */
    public boolean healthCheck() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), USER, USER);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (SQLException e) {
            log.warn("本地数据库健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    public String jdbcUrl() {
        if (postgres == null) {
            throw new IllegalStateException("本地数据库尚未启动");
        }
        return postgres.getJdbcUrl(USER, DATABASE);
    }

    public Path dataDirectory() {
        return dataDir;
    }

    @PreDestroy
    public synchronized void stop() {
        if (postgres == null) {
            return;
        }
        try {
            // close() 会先执行 fast shutdown，再在超时后强杀，避免留下僵尸进程
            postgres.close();
            log.info("本地数据库已关闭");
        } catch (IOException e) {
            log.warn("本地数据库关闭失败: {}", e.getMessage());
        } finally {
            postgres = null;
        }
    }
}
