package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRunEvent;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.PendingFileChange;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.capsule.agentkey.repository.ChatMessageRepository;
import com.capsule.agentkey.repository.PendingFileChangeRepository;
import com.capsule.agentkey.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * P5-14：ChangeSet 聚合、继续修复上下文与逆序撤回/冲突检测。
 */
@ExtendWith(MockitoExtension.class)
class ChangeSetServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private static final Long PROJECT_ID = 20L;

    @Mock
    private PendingFileChangeRepository changeRepo;
    @Mock
    private AgentRunRepository runRepo;
    @Mock
    private AgentRunEventRepository eventRepo;
    @Mock
    private ChatMessageRepository messageRepo;
    @Mock
    private ProjectRepository projectRepo;

    @TempDir
    Path workspace;

    private ChangeSetService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ChangeSetService(changeRepo, runRepo, eventRepo, messageRepo,
                projectRepo, new WorkspaceFileService(), mapper);
    }

    @Test
    void aggregatesMultipleRunsAndFiltersByRun() {
        AgentRun run1 = run(1L, "turn-1", AgentRun.RunState.SUCCEEDED,
                "{\"status\":\"COMPLETED\",\"reason\":\"VERIFY_PASSED\"}",
                LocalDateTime.now().minusHours(2));
        AgentRun run2 = run(2L, "turn-2", AgentRun.RunState.SUCCEEDED,
                "{\"status\":\"COMPLETED\",\"reason\":\"VERIFY_PASSED\"}",
                LocalDateTime.now().minusHours(1));
        when(runRepo.findByUserIdAndSessionIdOrderByCreateTimeDescIdDesc(USER_ID, SESSION_ID))
                .thenReturn(List.of(run2, run1));

        PendingFileChange change1 = change(1L, "turn-1", "a.txt", "A", "B", 1, 1, "PENDING");
        PendingFileChange change2 = change(2L, "turn-2", "b.txt", null, "C", 3, 0, "PENDING");
        when(changeRepo.findBySessionIdOrderByCreateTimeDescIdDesc(SESSION_ID))
                .thenReturn(List.of(change2, change1));

        Map<Long, List<AgentRunEvent>> events = Map.of(
                1L, List.of(event(AgentRunEvent.EventType.VERIFY,
                        "{\"status\":\"PASSED\",\"message\":\"ok\",\"turnId\":\"turn-1\"}")),
                2L, List.of(event(AgentRunEvent.EventType.VERIFY,
                        "{\"status\":\"PASSED\",\"message\":\"ok\",\"turnId\":\"turn-2\"}")));
        when(eventRepo.findByRunIdOrderBySeqAsc(anyLong()))
                .thenAnswer(inv -> events.getOrDefault(inv.getArgument(0), List.of()));

        ChangeSetService.ChangeSetView view = service.view(USER_ID, SESSION_ID, null);

        assertThat(view.runs()).hasSize(2);
        assertThat(view.changes()).hasSize(2);
        assertThat(view.totals().files()).isEqualTo(2);
        assertThat(view.totals().additions()).isEqualTo(4);
        assertThat(view.totals().deletions()).isEqualTo(1);
        assertThat(view.verification().status()).isEqualTo("PASSED");
        assertThat(view.status()).isEqualTo("VERIFIED");
        assertThat(view.changes()).anySatisfy(change -> {
            assertThat(change.runId()).isEqualTo(2L);
            assertThat(change.turnId()).isEqualTo("turn-2");
        });

        ChangeSetService.ChangeSetView filtered = service.view(USER_ID, SESSION_ID, 1L);
        assertThat(filtered.runs()).hasSize(1);
        assertThat(filtered.runs().get(0).runId()).isEqualTo(1L);
        assertThat(filtered.changes()).hasSize(1);
        assertThat(filtered.changes().get(0).filePath()).isEqualTo("a.txt");
        assertThat(filtered.totals().files()).isEqualTo(1);
    }

    @Test
    void buildsContinuePromptWithFailureAndUnfinishedPlan() {
        AgentRun run = run(2L, "turn-2", AgentRun.RunState.SUCCEEDED, null, LocalDateTime.now());
        when(runRepo.findByUserIdAndSessionIdOrderByCreateTimeDescIdDesc(USER_ID, SESSION_ID))
                .thenReturn(List.of(run));
        when(changeRepo.findBySessionIdOrderByCreateTimeDescIdDesc(SESSION_ID))
                .thenReturn(List.of(change(2L, "turn-2", "a.txt", "A", "B", 1, 1, "PENDING")));

        AgentRunEvent completion = event(AgentRunEvent.EventType.COMPLETION,
                "{\"status\":\"UNVERIFIED\",\"reason\":\"VERIFY_FAILED\",\"message\":\"mvn test 失败\",\"filesChanged\":true}");
        AgentRunEvent verify = event(AgentRunEvent.EventType.VERIFY,
                "{\"status\":\"FAILED\",\"message\":\"mvn test 失败\",\"turnId\":\"turn-2\",\"commands\":["
                        + "{\"name\":\"test\",\"command\":\"mvn test\",\"exitCode\":1,\"outputTail\":\"AssertionFailed boom\"}]}");
        AgentRunEvent diagnostics = event(AgentRunEvent.EventType.DIAGNOSTICS,
                "{\"provider\":\"javac\",\"available\":true,\"turnId\":\"turn-2\",\"diagnostics\":["
                        + "{\"file\":\"A.java\",\"line\":12,\"severity\":\"ERROR\",\"message\":\"cannot find symbol\"}]}");
        when(eventRepo.findByRunIdOrderBySeqAsc(2L))
                .thenReturn(List.of(completion, verify, diagnostics));

        ChatMessage assistant = new ChatMessage();
        assistant.setRole(ChatMessage.MessageRole.ASSISTANT);
        assistant.setTrace("{\"plan\":{\"steps\":["
                + "{\"step\":\"修复解析\",\"status\":\"pending\"},"
                + "{\"step\":\"已完成步骤\",\"status\":\"completed\"}]}}");
        when(messageRepo.findBySessionIdOrderByCreateTimeAsc(SESSION_ID))
                .thenReturn(List.of(assistant));

        ChangeSetService.ChangeSetView view = service.view(USER_ID, SESSION_ID, null);

        assertThat(view.status()).isEqualTo("UNVERIFIED");
        assertThat(view.verification().status()).isEqualTo("FAILED");
        assertThat(view.continuePrompt())
                .contains("继续修复上一个任务")
                .contains("mvn test 失败")
                .contains("AssertionFailed boom")
                .contains("A.java:12")
                .contains("修复解析")
                .contains("不要从头重做");
    }

    @Test
    void acceptingChangesDoesNotTurnUnverifiedIntoVerified() {
        AgentRun run = run(1L, "turn-1", AgentRun.RunState.SUCCEEDED,
                "{\"status\":\"UNVERIFIED\",\"reason\":\"VERIFY_NOT_CONFIGURED\"}",
                LocalDateTime.now().minusHours(1));
        when(runRepo.findByUserIdAndSessionIdOrderByCreateTimeDescIdDesc(USER_ID, SESSION_ID))
                .thenReturn(List.of(run));
        when(changeRepo.findBySessionIdOrderByCreateTimeDescIdDesc(SESSION_ID))
                .thenReturn(List.of(change(1L, "turn-1", "a.txt", "A", "B", 1, 1, "ACCEPTED")));
        when(eventRepo.findByRunIdOrderBySeqAsc(1L)).thenReturn(List.of(event(
                AgentRunEvent.EventType.VERIFY,
                "{\"status\":\"NOT_CONFIGURED\",\"message\":\"未配置验证命令\",\"turnId\":\"turn-1\"}")));

        ChangeSetService.ChangeSetView view = service.view(USER_ID, SESSION_ID, null);

        assertThat(view.accepted()).isTrue();
        assertThat(view.verification().status()).isEqualTo("NOT_CONFIGURED");
        assertThat(view.status()).isEqualTo("UNVERIFIED");
        assertThat(view.changes().get(0).status()).isEqualTo("ACCEPTED");
    }

    @Test
    void revertSessionRestoresSameFileInReverseOrder() throws Exception {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "C", StandardCharsets.UTF_8);
        stubProject();

        PendingFileChange newer = change(2L, "turn-2", "a.txt", "B", "C", 1, 1, "PENDING");
        PendingFileChange older = change(1L, "turn-1", "a.txt", "A", "B", 1, 1, "PENDING");
        when(changeRepo.findBySessionIdOrderByCreateTimeDescIdDesc(SESSION_ID))
                .thenReturn(List.of(newer, older));

        ChangeSetService.RevertResult result = service.revertSession(SESSION_ID);

        assertThat(result.count()).isEqualTo(2);
        assertThat(result.conflicts()).isEmpty();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("A");
        assertThat(newer.getStatus()).isEqualTo("REVERTED");
        assertThat(older.getStatus()).isEqualTo("REVERTED");
    }

    @Test
    void revertRunOnlyTouchesTargetTurn() throws Exception {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "C", StandardCharsets.UTF_8);
        stubProject();

        AgentRun run2 = run(2L, "turn-2", AgentRun.RunState.SUCCEEDED, null, LocalDateTime.now());
        when(runRepo.findById(2L)).thenReturn(Optional.of(run2));

        PendingFileChange newer = change(2L, "turn-2", "a.txt", "B", "C", 1, 1, "PENDING");
        PendingFileChange older = change(1L, "turn-1", "a.txt", "A", "B", 1, 1, "PENDING");
        when(changeRepo.findBySessionIdOrderByCreateTimeDescIdDesc(SESSION_ID))
                .thenReturn(List.of(newer, older));

        ChangeSetService.RevertResult result = service.revertRun(SESSION_ID, 2L, USER_ID);

        assertThat(result.count()).isEqualTo(1);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("B");
        assertThat(newer.getStatus()).isEqualTo("REVERTED");
        assertThat(older.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void externalModificationReturnsConflictWithoutOverwriting() throws Exception {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "D", StandardCharsets.UTF_8);
        stubProject();

        PendingFileChange change = change(1L, "turn-1", "a.txt", "B", "C", 1, 1, "PENDING");
        when(changeRepo.findBySessionIdOrderByCreateTimeDescIdDesc(SESSION_ID))
                .thenReturn(List.of(change));

        ChangeSetService.RevertResult result = service.revertSession(SESSION_ID);

        assertThat(result.count()).isZero();
        assertThat(result.conflicts()).hasSize(1);
        assertThat(result.conflicts().get(0).reason()).contains("外部改动");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("D");
        assertThat(change.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void acceptedChangesAreSkippedAndKeptForAudit() throws Exception {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "C", StandardCharsets.UTF_8);
        stubProject();

        PendingFileChange accepted = change(1L, "turn-1", "a.txt", "A", "C", 1, 1, "ACCEPTED");
        PendingFileChange pending = change(2L, "turn-2", "b.txt", null, "B", 1, 0, "PENDING");
        when(changeRepo.findBySessionIdOrderByCreateTimeDescIdDesc(SESSION_ID))
                .thenReturn(List.of(pending, accepted));

        ChangeSetService.RevertResult result = service.revertSession(SESSION_ID);

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.skippedAccepted()).isEqualTo(1);
        assertThat(Files.exists(workspace.resolve("b.txt"))).isFalse();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("C");
        assertThat(accepted.getStatus()).isEqualTo("ACCEPTED");
    }

    // ---------------------------------------------------------------------

    private void stubProject() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setWorkspacePath(workspace.toAbsolutePath().toString());
        when(projectRepo.findById(PROJECT_ID)).thenReturn(Optional.of(project));
    }

    private AgentRun run(Long id, String turnId, AgentRun.RunState state, String resultJson,
                         LocalDateTime createTime) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setUserId(USER_ID);
        run.setSessionId(SESSION_ID);
        run.setProjectId(PROJECT_ID);
        run.setState(state);
        run.setRequestJson("{\"prompt\":\"修复解析\",\"turnId\":\"" + turnId + "\"}");
        run.setResultJson(resultJson);
        run.setCreateTime(createTime);
        run.setEndTime(createTime.plusMinutes(1));
        return run;
    }

    private PendingFileChange change(Long id, String turnId, String path, String oldContent,
                                     String newContent, int additions, int deletions, String status) {
        PendingFileChange change = new PendingFileChange();
        change.setId(id);
        change.setUserId(USER_ID);
        change.setSessionId(SESSION_ID);
        change.setProjectId(PROJECT_ID);
        change.setTurnId(turnId);
        change.setFilePath(path);
        change.setAction(oldContent == null ? "create" : "modify");
        change.setSource("edit_file");
        change.setOldContent(oldContent);
        change.setNewContent(newContent);
        change.setAdditions(additions);
        change.setDeletions(deletions);
        change.setDiffText("diff");
        change.setStatus(status);
        change.setCreateTime(LocalDateTime.now().minusMinutes(id));
        return change;
    }

    private AgentRunEvent event(AgentRunEvent.EventType type, String payload) {
        AgentRunEvent event = new AgentRunEvent();
        event.setEventType(type);
        event.setPayload(payload);
        return event;
    }
}
