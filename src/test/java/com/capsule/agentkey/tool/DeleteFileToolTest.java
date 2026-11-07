package com.capsule.agentkey.tool;

import com.capsule.agentkey.entity.PendingFileChange;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.repository.PendingFileChangeRepository;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.WorkspaceFileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeleteFileToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workspace;

    private PendingFileChangeRepository pfcRepo;
    private DeleteFileTool tool;

    @BeforeEach
    void setUp() {
        ProjectService projectService = mock(ProjectService.class);
        pfcRepo = mock(PendingFileChangeRepository.class);
        Project project = new Project();
        project.setWorkspacePath(workspace.toString());
        when(projectService.getOwned(7L, 11L)).thenReturn(project);
        tool = new DeleteFileTool(projectService, new WorkspaceFileService(), pfcRepo);

        WorkspaceContext.set(7L, 11L);
        WorkspaceContext.get().setSessionId(23L);
        WorkspaceContext.get().setTurnId("turn-1");
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    void 删除文本文件前保存可撤回快照() throws Exception {
        Files.writeString(workspace.resolve("note.txt"), "hello\nworld\n");

        ToolResult result = tool.execute(arguments("note.txt", false));

        assertTrue(result.isSuccess());
        assertFalse(Files.exists(workspace.resolve("note.txt")));
        ArgumentCaptor<PendingFileChange> captor = ArgumentCaptor.forClass(PendingFileChange.class);
        verify(pfcRepo).save(captor.capture());
        PendingFileChange change = captor.getValue();
        assertEquals(7L, change.getUserId());
        assertEquals(11L, change.getProjectId());
        assertEquals(23L, change.getSessionId());
        assertEquals("turn-1", change.getTurnId());
        assertEquals("note.txt", change.getFilePath());
        assertEquals("delete", change.getAction());
        assertEquals("delete_file", change.getSource());
        assertEquals("hello\nworld\n", change.getOldContent());
        assertEquals("", change.getNewContent());
        assertEquals(1, WorkspaceContext.get().fileChanges().size());
    }

    @Test
    void 非空目录未显式递归时拒绝删除() throws Exception {
        Path directory = Files.createDirectory(workspace.resolve("docs"));
        Path file = Files.writeString(directory.resolve("a.txt"), "keep");

        ToolResult result = tool.execute(arguments("docs", false));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("recursive=true"));
        assertTrue(Files.exists(file));
        verifyNoInteractions(pfcRepo);
    }

    @Test
    void 显式递归删除目录时逐文件记录审查流() throws Exception {
        Path directory = Files.createDirectory(workspace.resolve("docs"));
        Files.writeString(directory.resolve("a.txt"), "a");
        Files.writeString(directory.resolve("b.txt"), "b");

        ToolResult result = tool.execute(arguments("docs", true));

        assertTrue(result.isSuccess());
        assertFalse(Files.exists(directory));
        verify(pfcRepo, times(2)).save(org.mockito.ArgumentMatchers.any(PendingFileChange.class));
        assertEquals(2, WorkspaceContext.get().fileChanges().size());
    }

    @Test
    void 工作区根目录与Git目录不能删除() throws Exception {
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve(".git/config"), "keep");

        ToolResult rootResult = tool.execute(arguments(".", true));
        ToolResult gitResult = tool.execute(arguments(".git/config", false));

        assertFalse(rootResult.isSuccess());
        assertTrue(rootResult.getError().contains("根目录"));
        assertFalse(gitResult.isSuccess());
        assertTrue(gitResult.getError().contains(".git"));
        assertTrue(Files.exists(workspace.resolve(".git/config")));
        verifyNoInteractions(pfcRepo);
    }

    private ObjectNode arguments(String path, boolean recursive) {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("path", path);
        arguments.put("recursive", recursive);
        return arguments;
    }
}
