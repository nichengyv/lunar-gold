package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.ChatSession;
import com.capsule.agentkey.entity.Document;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.repository.ChatMessageRepository;
import com.capsule.agentkey.repository.ChatSessionRepository;
import com.capsule.agentkey.repository.DocumentChunkRepository;
import com.capsule.agentkey.repository.DocumentRepository;
import com.capsule.agentkey.repository.ProjectRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * 项目（工作区）服务。
 *
 * 设计原则：项目是“资料与对话的容器”，用户不需要理解它也能用——
 * 登录后自动拥有默认项目，前端不传 projectId 时一律回落到默认项目。
 */
@Service
@Slf4j
public class ProjectService {

    private static final String DEFAULT_NAME = "默认项目";
    private static final String DEFAULT_DESCRIPTION = "未归类的资料与对话";
    /** 项目默认权限的可选值，与 AgentPolicy.Permission 保持一致 */
    private static final Set<String> VALID_PERMISSIONS = Set.of("MANUAL", "AUTO", "FULL");

    private final ProjectRepository projectRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final VectorStoreService vectorStoreService;

    public ProjectService(ProjectRepository projectRepository,
                          ChatSessionRepository sessionRepository,
                          ChatMessageRepository messageRepository,
                          DocumentRepository documentRepository,
                          DocumentChunkRepository chunkRepository,
                          VectorStoreService vectorStoreService) {
        this.projectRepository = projectRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.vectorStoreService = vectorStoreService;
    }

    /** 取得（必要时创建）默认项目；并发首次创建由唯一索引兜底，冲突时回查一次 */
    @Transactional
    public Project ensureDefault(Long userId) {
        return projectRepository.findFirstByUserIdAndDefaultFlagTrue(userId)
                .orElseGet(() -> {
                    Project p = new Project();
                    p.setUserId(userId);
                    p.setName(DEFAULT_NAME);
                    p.setDescription(DEFAULT_DESCRIPTION);
                    p.setDefaultFlag(true);
                    Project saved = projectRepository.save(p);
                    log.info("已创建默认项目 [userId={}, projectId={}]", userId, saved.getId());
                    return saved;
                });
    }

    @Transactional
    public List<Project> listByUserId(Long userId) {
        ensureDefault(userId);
        return projectRepository.findByUserIdOrderByDefaultFlagDescCreateTimeAsc(userId);
    }

    @Transactional
    public Project create(Long userId, String name, String description) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException("项目名称不能为空");
        }
        if (trimmed.length() > 100) {
            throw new BusinessException("项目名称不能超过 100 个字符");
        }
        Project p = new Project();
        p.setUserId(userId);
        p.setName(trimmed);
        p.setDescription(description);
        p.setDefaultFlag(false);
        return projectRepository.save(p);
    }

    /** 校验项目归属；不存在或不属于当前用户都统一提示"项目不存在" */
    public Project getOwned(Long userId, Long projectId) {
        return projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new BusinessException("项目不存在"));
    }

    /** 更新项目自定义指令（空字符串等同于清空） */
    @Transactional
    public Project updateInstruction(Long userId, Long projectId, String instruction) {
        Project p = getOwned(userId, projectId);
        p.setInstruction((instruction == null || instruction.isBlank()) ? null : instruction.trim());
        return projectRepository.save(p);
    }

    /** 设置项目默认工作权限：空值 = 不设置（新会话按可执行处理）；非法值直接拒绝，避免静默回落 */
    @Transactional
    public Project updateDefaultPermission(Long userId, Long projectId, String permission) {
        Project p = getOwned(userId, projectId);
        if (permission == null || permission.isBlank()) {
            p.setDefaultPermission(null);
            return projectRepository.save(p);
        }
        String normalized = permission.trim().toUpperCase();
        if (!VALID_PERMISSIONS.contains(normalized)) {
            throw new BusinessException("未知的工作权限：" + permission + "，可选 MANUAL / AUTO / FULL");
        }
        p.setDefaultPermission(normalized);
        log.info("工作区默认权限更新 [userId={}, projectId={}, permission={}]", userId, projectId, normalized);
        return projectRepository.save(p);
    }

    /**
     * 绑定工作区磁盘目录。
     * 校验：路径非空、存在、是目录、解析为绝对路径后存库。
     * 沙箱越界校验由 WorkspaceFileService 在每次文件操作时执行（以 workspacePath 为根）。
     */
    @Transactional
    public Project setWorkspacePath(Long userId, Long projectId, String path) {
        if (path == null || path.isBlank()) {
            throw new BusinessException("工作区路径不能为空");
        }
        java.nio.file.Path p = java.nio.file.Paths.get(path.trim()).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(p)) {
            throw new BusinessException("路径不存在或不是文件夹：" + p);
        }
        Project project = getOwned(userId, projectId);
        project.setWorkspacePath(p.toString());
        log.info("工作区绑定 [userId={}, projectId={}, path={}]", userId, projectId, p);
        return projectRepository.save(project);
    }

    /**
     * 删除工作区：连同其下会话、消息、资料（向量 + chunk + 落盘文件）一起清掉。
     * 只删数据库记录，不会动用户磁盘上绑定的代码文件夹——那个目录属于用户，不归应用管。
     * 默认项目不允许删：前端不传 projectId 时会回落到它，删掉会导致旧调用无处可去。
     */
    @Transactional
    public void deleteOwned(Long userId, Long projectId) {
        Project project = getOwned(userId, projectId);
        if (project.isDefaultFlag()) {
            throw new BusinessException("默认工作区不能删除");
        }

        List<ChatSession> sessions = sessionRepository
                .findByUserIdAndProjectIdOrderByUpdateTimeDesc(userId, projectId);
        for (ChatSession session : sessions) {
            messageRepository.deleteBySessionId(session.getId());
            sessionRepository.delete(session);
        }

        List<Document> documents = documentRepository
                .findByUserIdAndProjectIdOrderByCreateTimeDesc(userId, projectId);
        for (Document doc : documents) {
            try {
                vectorStoreService.deleteByDocumentId(userId, doc.getId());
            } catch (Exception e) {
                log.warn("删除资料向量失败，继续删除记录 [docId={}]: {}", doc.getId(), e.getMessage());
            }
            chunkRepository.deleteByDocumentId(doc.getId());
            try {
                Files.deleteIfExists(Paths.get(doc.getStoragePath()));
            } catch (IOException e) {
                log.warn("删除资料文件失败: {}", doc.getStoragePath());
            }
            documentRepository.delete(doc);
        }

        projectRepository.delete(project);
        log.info("工作区已删除 [userId={}, projectId={}, sessions={}, documents={}]",
                userId, projectId, sessions.size(), documents.size());
    }

    /**
     * 把前端传来的 projectId 归一化为当前用户可用的项目：
     * 为空 → 默认项目；非空 → 校验归属，避免跨用户读写。
     */
    public Long resolveProjectId(Long userId, Long projectId) {
        if (projectId == null) {
            return ensureDefault(userId).getId();
        }
        return getOwned(userId, projectId).getId();
    }
}
