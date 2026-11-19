package com.capsule.agentkey.config;

import com.capsule.agentkey.controller.HealthController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6-01：公开子集契约快照。
 *
 * 快照只含「方法 + 路径 + scope + group」，不含请求/响应 schema；
 * 新增或删除公开端点必须同步改 src/test/resources/contract/public-api.json，否则本测试失败。
 */
class PublicApiContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    record Snapshot(int contractVersion, List<PublicApiCatalog.PublicEndpoint> endpoints) {
    }

    @Test
    void 清单与契约快照逐行一致() throws Exception {
        Snapshot snapshot = loadSnapshot();
        assertEquals(HealthController.API_CONTRACT_VERSION, snapshot.contractVersion(),
                "健康接口公布的契约版本必须与快照一致");

        PublicApiCatalog catalog = newCatalog();
        catalog.apply(PublicApiCatalog.declaredEndpoints());

        assertEquals(snapshot.endpoints(), catalog.endpoints());
    }

    @Test
    void 声明了未登记端点时自检失败并指出条目() {
        PublicApiCatalog catalog = newCatalog();
        List<PublicApiCatalog.PublicEndpoint> scanned =
                new ArrayList<>(PublicApiCatalog.declaredEndpoints());
        scanned.add(new PublicApiCatalog.PublicEndpoint("GET", "/api/keys", "project:read", "只读上下文"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> catalog.apply(scanned));
        assertTrue(error.getMessage().contains("GET /api/keys"), error.getMessage());
    }

    @Test
    void 清单登记了不存在的方法时自检失败() {
        PublicApiCatalog catalog = newCatalog();
        List<PublicApiCatalog.PublicEndpoint> scanned =
                new ArrayList<>(PublicApiCatalog.declaredEndpoints());
        scanned.removeIf(endpoint -> endpoint.path().equals("/api/health"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> catalog.apply(scanned));
        assertTrue(error.getMessage().contains("GET /api/health"), error.getMessage());
    }

    @Test
    void 路径匹配区分批量审批与单条审批() {
        PublicApiCatalog catalog = newCatalog();
        catalog.apply(PublicApiCatalog.declaredEndpoints());

        assertEquals("/api/agent/approvals/batch",
                catalog.find("POST", "/api/agent/approvals/batch").orElseThrow().path());
        assertEquals("/api/agent/approvals/{approvalId}",
                catalog.find("POST", "/api/agent/approvals/abc123").orElseThrow().path());
        assertEquals("run:read",
                catalog.find("GET", "/api/agent/sessions/9/messages").orElseThrow().scope());
        // 清单外接口与错误方法都不算公开端点
        assertTrue(catalog.find("GET", "/api/keys").isEmpty());
        assertTrue(catalog.find("DELETE", "/api/health").isEmpty());
    }

    private PublicApiCatalog newCatalog() {
        return new PublicApiCatalog(null);
    }

    private Snapshot loadSnapshot() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/contract/public-api.json")) {
            if (in == null) {
                throw new IllegalStateException("缺少契约快照 /contract/public-api.json");
            }
            return mapper.readValue(in, Snapshot.class);
        }
    }
}
