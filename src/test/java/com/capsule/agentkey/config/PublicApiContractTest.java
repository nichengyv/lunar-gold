package com.capsule.agentkey.config;

import com.capsule.agentkey.controller.HealthController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void 代码里每个PublicApi注解都必须登记到清单() throws Exception {
        Map<String, PublicApiCatalog.PublicEndpoint> annotated = byKey(scanAnnotatedEndpoints());
        Map<String, PublicApiCatalog.PublicEndpoint> declared =
                byKey(PublicApiCatalog.declaredEndpoints());

        assertEquals(declared, annotated,
                "新增或删除 @PublicApi 端点必须同步 PublicApiCatalog.DECLARED；"
                        + "漏登记会让应用启动自检直接失败（R7b 已踩过一次）");
    }

    private PublicApiCatalog newCatalog() {
        return new PublicApiCatalog(null);
    }

    private Map<String, PublicApiCatalog.PublicEndpoint> byKey(
            List<PublicApiCatalog.PublicEndpoint> endpoints) {
        Map<String, PublicApiCatalog.PublicEndpoint> result = new LinkedHashMap<>();
        for (PublicApiCatalog.PublicEndpoint endpoint : endpoints) {
            result.put(endpoint.method() + " " + endpoint.path(), endpoint);
        }
        return result;
    }

    /** 反射扫描 controller 包，等价于启动时 RequestMappingHandlerMapping 的公开端点发现，但无需启动 Spring */
    private List<PublicApiCatalog.PublicEndpoint> scanAnnotatedEndpoints() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        SimpleMetadataReaderFactory readerFactory = new SimpleMetadataReaderFactory();
        List<PublicApiCatalog.PublicEndpoint> endpoints = new ArrayList<>();
        for (Resource resource
                : resolver.getResources("classpath*:com/capsule/agentkey/controller/**/*.class")) {
            String className = readerFactory.getMetadataReader(resource)
                    .getClassMetadata().getClassName();
            Class<?> type = Class.forName(className, false, getClass().getClassLoader());
            RequestMapping typeMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] basePaths = typeMapping == null ? new String[]{""} : mappingPaths(typeMapping);
            for (Method method : type.getDeclaredMethods()) {
                PublicApi api = method.getAnnotation(PublicApi.class);
                if (api == null) {
                    continue;
                }
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null || mapping.method().length == 0) {
                    throw new IllegalStateException("@PublicApi 方法必须声明明确的 HTTP 方法："
                            + className + "#" + method.getName());
                }
                for (String base : basePaths) {
                    for (String path : mappingPaths(mapping)) {
                        for (RequestMethod httpMethod : mapping.method()) {
                            endpoints.add(new PublicApiCatalog.PublicEndpoint(
                                    httpMethod.name(), joinPath(base, path), api.scope(), api.group()));
                        }
                    }
                }
            }
        }
        return endpoints;
    }

    private String[] mappingPaths(RequestMapping mapping) {
        String[] paths = mapping.path().length > 0 ? mapping.path() : mapping.value();
        // 裸 @GetMapping / @PostMapping（路径只写在类级）时 path() 为空，按空串处理
        return paths.length > 0 ? paths : new String[]{""};
    }

    private String joinPath(String base, String path) {
        String left = base == null ? "" : base;
        String right = path == null ? "" : path;
        if (left.isEmpty()) {
            return right.startsWith("/") ? right : "/" + right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left.endsWith("/") || right.startsWith("/") ? left + right : left + "/" + right;
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
