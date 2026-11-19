package com.capsule.agentkey.config;

import com.capsule.agentkey.security.ApiScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * P6-01：公开子集清单。
 *
 * 启动时扫描 {@link RequestMappingHandlerMapping}，把带 {@link PublicApi} 的方法整理成
 * 「METHOD + 路径模板 → scope」映射，并与下方 {@link #DECLARED} 双向比对：
 * <ul>
 *   <li>注解声明了但清单没登记 → 启动失败；</li>
 *   <li>清单登记了但方法不存在 / 忘了加注解 / scope 不一致 → 启动失败；</li>
 *   <li>同一 METHOD + 路径重复声明 → 启动失败。</li>
 * </ul>
 *
 * 这样「哪些接口对客户端开放」有两处独立表达，任何一处改动漏了另一处都会在启动时炸掉，
 * 不会出现「悄悄多开放一个接口」。
 */
@Slf4j
@Service
public class PublicApiCatalog implements SmartInitializingSingleton {

    public static final String GROUP_TASK = "任务闭环";
    public static final String GROUP_CONTEXT = "只读上下文";
    public static final String GROUP_SELF = "客户端自管理";

    /** 契约清单（唯一人工维护处，新增/删除公开端点必须同步改这里与测试快照） */
    private static final List<PublicEndpoint> DECLARED = List.of(
            // 任务闭环
            new PublicEndpoint("POST", "/api/agent/chat/stream", ApiScope.CHAT_WRITE.value(), GROUP_TASK),
            new PublicEndpoint("GET", "/api/agent/runs/{runId}/events", ApiScope.RUN_READ.value(), GROUP_TASK),
            new PublicEndpoint("POST", "/api/agent/chat/stop", ApiScope.CHAT_WRITE.value(), GROUP_TASK),
            new PublicEndpoint("GET", "/api/file-changes/changeset", ApiScope.CHANGES_READ.value(), GROUP_TASK),
            new PublicEndpoint("POST", "/api/file-changes/revert-run", ApiScope.CHANGES_WRITE.value(), GROUP_TASK),
            new PublicEndpoint("POST", "/api/file-changes/revert-session", ApiScope.CHANGES_WRITE.value(), GROUP_TASK),
            new PublicEndpoint("POST", "/api/agent/approvals/{approvalId}", ApiScope.APPROVAL_WRITE.value(), GROUP_TASK),
            new PublicEndpoint("POST", "/api/agent/approvals/batch", ApiScope.APPROVAL_WRITE.value(), GROUP_TASK),
            new PublicEndpoint("GET", "/api/agent/sessions/{sessionId}/approvals", ApiScope.RUN_READ.value(), GROUP_TASK),
            // 只读上下文
            new PublicEndpoint("GET", "/api/projects", ApiScope.PROJECT_READ.value(), GROUP_CONTEXT),
            new PublicEndpoint("GET", "/api/agent/sessions", ApiScope.PROJECT_READ.value(), GROUP_CONTEXT),
            new PublicEndpoint("GET", "/api/agent/sessions/{sessionId}/messages", ApiScope.RUN_READ.value(), GROUP_CONTEXT),
            // 客户端自管理
            new PublicEndpoint("GET", "/api/clients/self", ApiScope.CLIENT_ADMIN.value(), GROUP_SELF),
            new PublicEndpoint("DELETE", "/api/clients/self", ApiScope.CLIENT_ADMIN.value(), GROUP_SELF),
            // 兼容性探测：只需要一个有效客户端令牌
            new PublicEndpoint("GET", "/api/health", "", GROUP_SELF)
    );

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /** 扫描结果，按 METHOD + 路径排序，保证确定性 */
    private volatile List<PublicEndpoint> endpoints = List.of();

    /**
     * 必须按 bean 名限定：Actuator 会额外注册 {@code controllerEndpointHandlerMapping}，
     * 不限定则启动时因「找到 2 个 RequestMappingHandlerMapping」直接失败。
     * 且只能扫 MVC 的那一个，否则扫不到本项目 controller，自检会把清单全判为未登记。
     */
    public PublicApiCatalog(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider) {
        this.handlerMappingProvider = handlerMappingProvider;
    }

    /** METHOD + 路径模板 + scope + group，契约快照与鉴权判断的唯一结构 */
    public record PublicEndpoint(String method, String path, String scope, String group) {

        public String key() {
            return method + " " + path;
        }
    }

    /** 契约清单只读视图：测试用它生成快照，生产代码不要改返回值 */
    public static List<PublicEndpoint> declaredEndpoints() {
        return DECLARED;
    }

    @Override
    public void afterSingletonsInstantiated() {
        RequestMappingHandlerMapping mapping = handlerMappingProvider.getIfAvailable();
        if (mapping == null) {
            log.warn("未找到 RequestMappingHandlerMapping，跳过公开子集自检");
            return;
        }
        apply(scan(mapping));
    }

    /**
     * 应用扫描结果：校验与清单一致后生效。
     * 生产启动路径由 {@link #afterSingletonsInstantiated()} 调用；测试也用它注入固定清单。
     */
    public void apply(List<PublicEndpoint> scanned) {
        Map<String, PublicEndpoint> byKey = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        for (PublicEndpoint endpoint : scanned) {
            if (!endpoint.scope().isBlank() && ApiScope.fromValue(endpoint.scope()).isEmpty()) {
                problems.add("未知 scope: " + endpoint.key() + " → " + endpoint.scope());
            }
            if (byKey.putIfAbsent(endpoint.key(), endpoint) != null) {
                problems.add("重复声明: " + endpoint.key());
            }
        }

        Map<String, PublicEndpoint> declared = new LinkedHashMap<>();
        for (PublicEndpoint endpoint : DECLARED) {
            declared.put(endpoint.key(), endpoint);
        }

        for (PublicEndpoint endpoint : byKey.values()) {
            PublicEndpoint expected = declared.get(endpoint.key());
            if (expected == null) {
                problems.add("声明了但清单未登记: " + endpoint.key());
            } else if (!expected.scope().equals(endpoint.scope()) || !expected.group().equals(endpoint.group())) {
                problems.add("scope / group 与清单不一致: " + endpoint.key());
            }
        }
        for (PublicEndpoint endpoint : declared.values()) {
            if (!byKey.containsKey(endpoint.key())) {
                problems.add("清单登记了但方法不存在或缺少 @PublicApi: " + endpoint.key());
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("公开子集清单自检失败：" + String.join("；", problems));
        }

        this.endpoints = byKey.values().stream()
                .sorted((a, b) -> a.key().compareTo(b.key()))
                .toList();
        log.info("公开子集清单已生效，共 {} 个端点", endpoints.size());
    }

    /** 按 HTTP 方法与实际路径匹配公开端点；多条模板命中时取更具体的一条 */
    public Optional<PublicEndpoint> find(String httpMethod, String path) {
        PublicEndpoint best = null;
        for (PublicEndpoint endpoint : endpoints) {
            if (!"ANY".equals(endpoint.method()) && !endpoint.method().equalsIgnoreCase(httpMethod)) {
                continue;
            }
            if (!pathMatcher.match(endpoint.path(), path)) {
                continue;
            }
            if (best == null
                    || pathMatcher.getPatternComparator(path).compare(best.path(), endpoint.path()) > 0) {
                best = endpoint;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 当前生效的公开子集（契约快照使用），已按 key 排序 */
    public List<PublicEndpoint> endpoints() {
        return endpoints;
    }

    private List<PublicEndpoint> scan(RequestMappingHandlerMapping mapping) {
        List<PublicEndpoint> scanned = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
            PublicApi annotation = entry.getValue().getMethodAnnotation(PublicApi.class);
            if (annotation == null) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            Set<String> patterns = info.getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (methods.isEmpty()) {
                    scanned.add(new PublicEndpoint("ANY", pattern, annotation.scope(), annotation.group()));
                    continue;
                }
                for (RequestMethod method : methods) {
                    scanned.add(new PublicEndpoint(method.name(), pattern, annotation.scope(), annotation.group()));
                }
            }
        }
        return scanned;
    }
}
