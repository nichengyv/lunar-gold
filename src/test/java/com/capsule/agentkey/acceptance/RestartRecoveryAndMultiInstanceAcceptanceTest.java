package com.capsule.agentkey.acceptance;

import com.capsule.agentkey.config.PendingFileChangePrecheckCallback;
import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRun.RunState;
import com.capsule.agentkey.entity.AgentRunEvent;
import com.capsule.agentkey.entity.AgentRunEvent.EventType;
import com.capsule.agentkey.entity.ToolApproval;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.capsule.agentkey.repository.ToolApprovalRepository;
import com.capsule.agentkey.service.AgentRunService;
import com.capsule.agentkey.service.ToolApprovalRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B + D：Phase 4 出境验收——重启恢复与双实例共用同一 PostgreSQL。
 *
 * <p>使用真实 PostgreSQL + 真实 Spring Data Repository + 真实实体映射（Hibernate validate），
 * 每个测试方法不在测试事务内执行（NOT_SUPPORTED），让写入真正提交，从而能模拟
 * 「新进程/新实例」从库里读取遗留数据。
 */
@EnabledIfEnvironmentVariable(named = "DESKTOP_PG_RUNTIME", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RestartRecoveryAndMultiInstanceAcceptanceTest {

    private static final Path DATA_DIR;
    private static final EmbeddedPostgres POSTGRES;
    private static final String JDBC_URL;

    static {
        Path dir = null;
        EmbeddedPostgres postgres = null;
        String url = null;
        if (System.getenv("DESKTOP_PG_RUNTIME") != null) {
            try {
                dir = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "p4-bd-pg-");
                postgres = EmbeddedPgSupport.start(dir);
                url = EmbeddedPgSupport.jdbcUrl(postgres);
                // 先完成全量迁移，测试上下文用 ddl-auto=validate 校验实体与真实 schema 一致
                Flyway.configure()
                        .dataSource(url, EmbeddedPgSupport.USER, EmbeddedPgSupport.USER)
                        .locations("classpath:db/migration")
                        .callbacks(new PendingFileChangePrecheckCallback())
                        .load()
                        .migrate();
            } catch (Exception e) {
                throw new IllegalStateException("内嵌 PostgreSQL 启动或迁移失败", e);
            }
        }
        DATA_DIR = dir;
        POSTGRES = postgres;
        JDBC_URL = url;
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> EmbeddedPgSupport.USER);
        registry.add("spring.datasource.password", () -> EmbeddedPgSupport.USER);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private AgentRunRepository runRepo;
    @Autowired
    private AgentRunEventRepository eventRepo;
    @Autowired
    private ToolApprovalRepository approvalRepo;

    @AfterAll
    static void shutdown() throws Exception {
        if (POSTGRES != null) {
            POSTGRES.close();
        }
        if (DATA_DIR != null) {
            deleteRecursively(DATA_DIR);
        }
    }

    @BeforeEach
    void clean() {
        eventRepo.deleteAll();
        runRepo.deleteAll();
        approvalRepo.deleteAll();
    }

    // ------------------------------------------------------------------
    // B：重启恢复
    // ------------------------------------------------------------------

    @Test
    void 重启后遗留RUNNING与QUEUED变FAILED并写PROCESS_RESTARTED事件() {
        AgentRun queued = seedRun(1L, 10L, RunState.QUEUED);
        AgentRun running = seedRun(1L, 11L, RunState.RUNNING);

        // 模拟新进程启动：全新的 Service 实例扫描真实库
        AgentRunService restarted = new AgentRunService(runRepo, eventRepo, new ObjectMapper());
        restarted.recoverAfterRestart();

        AgentRun queuedAfter = runRepo.findById(queued.getId()).orElseThrow();
        assertEquals(RunState.FAILED, queuedAfter.getState());
        assertEquals("PROCESS_RESTARTED", queuedAfter.getErrorCode());
        assertNotNull(queuedAfter.getEndTime(), "中断运行应写入结束时间");

        AgentRun runningAfter = runRepo.findById(running.getId()).orElseThrow();
        assertEquals(RunState.FAILED, runningAfter.getState());
        assertEquals("PROCESS_RESTARTED", runningAfter.getErrorCode());
        assertNotNull(runningAfter.getEndTime());

        List<AgentRunEvent> events = eventRepo.findByRunIdOrderBySeqAsc(queued.getId());
        assertTrue(events.stream().anyMatch(e -> e.getEventType() == EventType.STATE_CHANGED
                        && e.getPayload() != null && e.getPayload().contains("PROCESS_RESTARTED")),
                "恢复动作必须留下可重放的状态事件");
    }

    @Test
    void 未过期审批重启可恢复并落库决策() {
        seedApproval("ap-live", 1L, 10L, "write_file", "WRITE", "PENDING",
                System.currentTimeMillis() + 60_000, false);
        seedApproval("ap-remember", 1L, 10L, "edit_file", "WRITE", "APPROVED",
                System.currentTimeMillis(), true);

        ToolApprovalRegistry restarted = new ToolApprovalRegistry(approvalRepo);
        restore(restarted);

        assertTrue(restarted.isSessionAllowed(10L, "edit_file"), "会话级「总是允许」应恢复");
        assertFalse(restarted.isSessionAllowed(11L, "edit_file"), "其他会话不应继承允许");

        assertTrue(restarted.resolve("ap-live", 1L, "APPROVE", false),
                "未过期审批恢复后应可被批准");

        ToolApproval updated = approvalRepo.findByApprovalId("ap-live").orElseThrow();
        assertEquals("APPROVED", updated.getState());
        assertNotNull(updated.getDecisionTime());
    }

    @Test
    void 过期审批重启即拒绝() {
        seedApproval("ap-expired", 1L, 10L, "delete_file", "DESTRUCTIVE", "PENDING",
                System.currentTimeMillis() - 1_000, false);

        ToolApprovalRegistry restarted = new ToolApprovalRegistry(approvalRepo);
        restore(restarted);

        ToolApproval updated = approvalRepo.findByApprovalId("ap-expired").orElseThrow();
        assertEquals("REJECTED", updated.getState(), "过期审批不能重新获得完整等待时间");
        assertNotNull(updated.getDecisionTime());
        assertFalse(restarted.resolve("ap-expired", 1L, "APPROVE", false),
                "过期审批不应还能被批准");
    }

    // ------------------------------------------------------------------
    // D：双实例共用同一 PostgreSQL
    // ------------------------------------------------------------------

    @Test
    void 双实例共享run状态() {
        AgentRunService instanceA = new AgentRunService(runRepo, eventRepo, new ObjectMapper());
        AgentRunService instanceB = new AgentRunService(runRepo, eventRepo, new ObjectMapper());

        AgentRunService.CreateResult created = instanceA.create(1L, 10L, 20L, "req-d", "{\"prompt\":\"hi\"}");
        Long runId = created.run().getId();

        assertEquals(RunState.QUEUED, instanceB.findById(runId).orElseThrow().getState(),
                "另一实例应能看到刚创建的 run");

        instanceA.start(runId);
        assertEquals(RunState.RUNNING, instanceB.findById(runId).orElseThrow().getState(),
                "RUNNING 状态应跨实例可见");

        instanceA.succeed(runId, "{\"ok\":true}");
        AgentRun finalByB = instanceB.findById(runId).orElseThrow();
        assertEquals(RunState.SUCCEEDED, finalByB.getState());
        assertEquals("{\"ok\":true}", finalByB.getResultJson());
    }

    @Test
    void 双实例审批决策落库可见_跨实例唤醒未实现() {
        seedApproval("ap-multi", 1L, 10L, "write_file", "WRITE", "PENDING",
                System.currentTimeMillis() + 60_000, false);

        ToolApprovalRegistry instanceA = new ToolApprovalRegistry(approvalRepo);
        ToolApprovalRegistry instanceB = new ToolApprovalRegistry(approvalRepo);
        restore(instanceA);
        restore(instanceB);

        assertTrue(instanceB.resolve("ap-multi", 1L, "APPROVE", false), "实例 B 应能提交决策");
        assertEquals("APPROVED", approvalRepo.findByApprovalId("ap-multi").orElseThrow().getState(),
                "决策应落在共享库，实例 A 也能读到");

        // 已知边界：两个实例各自持有内存 future，实例 A 不会因 B 的决策而被唤醒，
        // 且 A 仍能重复提交同一决策；跨实例互斥/唤醒未实现，报告中必须如实标注。
        assertTrue(instanceA.resolve("ap-multi", 1L, "APPROVE", false),
                "当前实现下实例 A 仍可重复接受决策（已知边界，不作为通过标准）");
        assertEquals("APPROVED", approvalRepo.findByApprovalId("ap-multi").orElseThrow().getState());
    }

    // ------------------------------------------------------------------
    // 数据准备
    // ------------------------------------------------------------------

    private AgentRun seedRun(Long userId, Long sessionId, RunState state) {
        AgentRun run = new AgentRun();
        run.setUserId(userId);
        run.setSessionId(sessionId);
        run.setState(state);
        run.setRequestJson("{\"prompt\":\"seed\"}");
        run.setCreateTime(LocalDateTime.now());
        run.setUpdateTime(LocalDateTime.now());
        if (state == RunState.RUNNING) {
            run.setStartTime(LocalDateTime.now());
        }
        return runRepo.saveAndFlush(run);
    }

    private ToolApproval seedApproval(String approvalId, Long userId, Long sessionId,
                                      String tool, String risk, String state,
                                      long expiresAt, boolean rememberSession) {
        ToolApproval approval = new ToolApproval();
        approval.setApprovalId(approvalId);
        approval.setUserId(userId);
        approval.setSessionId(sessionId);
        approval.setToolName(tool);
        approval.setArguments("{}");
        approval.setRisk(risk);
        approval.setState(state);
        approval.setExpiresAt(expiresAt);
        approval.setRememberSession(rememberSession);
        approval.setCreateTime(LocalDateTime.now());
        approval.setUpdateTime(LocalDateTime.now());
        return approvalRepo.saveAndFlush(approval);
    }

    /** restorePersistedApprovals 是 @PostConstruct 包级方法，模拟新实例启动时通过反射触发。 */
    private static void restore(ToolApprovalRegistry registry) {
        try {
            Method method = ToolApprovalRegistry.class.getDeclaredMethod("restorePersistedApprovals");
            method.setAccessible(true);
            method.invoke(registry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("触发审批恢复失败", e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响验收结论
                }
            });
        }
    }
}
