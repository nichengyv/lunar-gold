package com.capsule.agentkey.util;

import com.capsule.agentkey.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户解析。
 *
 * <p>SecurityContextHolder 存放本次请求的认证信息，JwtAuthenticationFilter / LocalTokenFilter
 * 把 userId 作为 principal 放进去，这里统一读取，避免每个 Controller 各写一份。
 *
 * <p>两种契约并存，调用方按场景选择：
 * <ul>
 *   <li>{@link #requireId()}：业务接口必须有明确归属，取不到直接拒绝；</li>
 *   <li>{@link #idOrNull()}：全局规则、按 IP 限流等允许匿名回退的场景。</li>
 * </ul>
 */
public final class CurrentUser {

    /** 未认证文案保持与历史接口一致，避免前端提示变化 */
    private static final String UNAUTHENTICATED = "用户未认证";

    private CurrentUser() {
    }

    /** 取当前用户 id；未认证时抛业务异常（HTTP 400，与既有行为一致） */
    public static Long requireId() {
        Long userId = idOrNull();
        if (userId == null) {
            throw new BusinessException(UNAUTHENTICATED);
        }
        return userId;
    }

    /** 取当前用户 id；未认证返回 null */
    public static Long idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // instanceof 同时完成类型判断与转换；principal 存的是 userId
        return auth != null && auth.getPrincipal() instanceof Long userId ? userId : null;
    }
}
