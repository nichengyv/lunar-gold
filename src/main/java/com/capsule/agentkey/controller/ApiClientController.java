package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.PublicApi;
import com.capsule.agentkey.config.PublicApiCatalog;
import com.capsule.agentkey.dto.ApiClientView;
import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.CreateClientRequest;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.security.ClientPrincipal;
import com.capsule.agentkey.security.CurrentClient;
import com.capsule.agentkey.service.ApiClientService;
import com.capsule.agentkey.util.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * P6-01：客户端管理端点。
 *
 * 两类调用者：
 * <ul>
 *   <li>本地用户令牌（Web 工作台 / 桌面端）：可创建客户端、查看/撤销任意属于自己的客户端；</li>
 *   <li>客户端令牌（lgc_...）：只能查看或撤销自己（{@code /self}），由
 *       {@link com.capsule.agentkey.filter.ScopeAuthorizationFilter} 限制 scope 为 client:admin。</li>
 * </ul>
 *
 * POST /api/clients 故意不进 {@link PublicApiCatalog}：客户端令牌无权创建客户端，
 * 这样 ScopeAuthorizationFilter 会直接 403，只有本地令牌能走到这里。
 */
@RestController
@RequestMapping("/api/clients")
public class ApiClientController {

    private final ApiClientService apiClientService;

    public ApiClientController(ApiClientService apiClientService) {
        this.apiClientService = apiClientService;
    }

    /**
     * 本地用户签发一个客户端令牌。
     *
     * 明文令牌只在这一次响应里返回，之后任何接口都不再回显；
     * 调用方（CLI / 插件）必须自行妥善保存，丢失后只能重新签发。
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateClientRequest request) {
        Long userId = CurrentUser.requireId();
        // 仅本地令牌可签发；客户端令牌走到这里说明 ScopeAuthorizationFilter 漏判了，兜底拒绝
        if (CurrentClient.get() != null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "客户端令牌无权创建新客户端");
        }
        ApiClientService.IssuedClient issued = apiClientService.create(userId, request.name(), request.scopes());
        return ApiResponse.success(Map.of(
                "token", issued.token(),
                "client", issued.client()));
    }

    /** 当前客户端信息：不含 secret；已撤销的令牌进不来（认证阶段就 401） */
    @GetMapping("/self")
    @PublicApi(scope = "client:admin", group = PublicApiCatalog.GROUP_SELF)
    public ApiResponse<ApiClientView> self() {
        ClientPrincipal client = requireClient();
        return ApiResponse.success(apiClientService.view(client.userId(), client.clientId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户端不存在")));
    }

    /** 撤销自己：置 revoked_at，下一次请求立即 401，CLI 需重新签发 */
    @DeleteMapping("/self")
    @PublicApi(scope = "client:admin", group = PublicApiCatalog.GROUP_SELF)
    public ResponseEntity<Void> revokeSelf() {
        ClientPrincipal client = requireClient();
        if (!apiClientService.revokeSelf(client.userId(), client.clientId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "客户端不存在");
        }
        return ResponseEntity.noContent().build();
    }

    private ClientPrincipal requireClient() {
        ClientPrincipal client = CurrentClient.get();
        if (client == null) {
            throw new BusinessException("当前请求不携带客户端令牌");
        }
        return client;
    }
}
