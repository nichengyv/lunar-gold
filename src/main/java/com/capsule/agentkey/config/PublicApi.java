package com.capsule.agentkey.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * P6-01：标记「对本地 API 客户端开放的公开子集」。
 *
 * 只加在 controller 方法上，不影响既有 Web / 云端行为：
 * 是否放行由 ScopeAuthorizationFilter 决定，且仅对携带客户端令牌（lgc_...）的请求生效。
 *
 * scope 为空表示「只需要一个有效客户端令牌」，用于 GET /api/health 这类兼容性探测。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicApi {

    /** 需要的客户端 scope，取值见 {@link com.capsule.agentkey.security.ApiScope}；空串表示不校验 scope */
    String scope() default "";

    /** 分组，仅用于契约快照与文档，不参与鉴权 */
    String group();
}
