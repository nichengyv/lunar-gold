package com.capsule.agentkey.exception;

import org.springframework.http.HttpStatus;

/**
 * 全局错误码契约（唯一事实来源）。
 *
 * 设计原则：同一个信息只能有一个来源。
 * HTTP 状态码与响应体里的 code 是同一件事的两种表达，因此这里只定义 httpStatus，
 * 业务码 code 由它派生（见 getCode()）——枚举内部也不允许出现第二个真相。
 *
 * 使用规则：
 * 1. 禁止在别处再写字面量 400/401/403/404/413/429/500，一律引用本枚举；
 * 2. 新增错误码只改这个文件，GlobalExceptionHandler / JwtAuthenticationFilter /
 *    RateLimitInterceptor / ResponseWriter 会自动跟随，不会再各自漂移。
 */
public enum ErrorCode {

    // ---------- 400 参数 / 业务 ----------
    PARAM_INVALID(HttpStatus.BAD_REQUEST, "参数校验失败"),
    BUSINESS_ERROR(HttpStatus.BAD_REQUEST, "业务处理失败"),
    UPLOAD_PROTOCOL_ERROR(HttpStatus.BAD_REQUEST, "文件上传格式错误"),

    // ---------- 401 未认证 ----------
    // 必须是 401 而不是 403：HTTP 语义中 401 = 未携带凭证 / 凭证无效，403 = 已认证但无权限。
    // 前端 static/js/api.js:25 依赖 401 触发"登录过期 → 清除 token 并跳回登录页"，
    // 若改成 403，登录过期流程会静默失效。
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未认证或登录已过期"),

    // ---------- 403 已认证但无权 ----------
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权访问该资源"),

    // ---------- 404 ----------
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),

    // ---------- 413 ----------
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "文件过大"),

    // ---------- 429 限流 ----------
    // 限流的正确语义就是 429，RateLimitInterceptor 原本返回的也是 429。
    // 旧的接口文档写"超限 code=400"是文档缺陷，应改文档，不能把代码降级。
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试"),

    // ---------- 500 ----------
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后重试"),
    IO_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "文件读写失败");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    /** HTTP 状态码：ResponseEntity 与 HttpServletResponse 都用它 */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /** 响应体里的业务码：由 httpStatus 派生，保证与 HTTP 状态码永远一致 */
    public int getCode() {
        return httpStatus.value();
    }

    /** 调用方未指定文案时使用的默认提示 */
    public String getDefaultMessage() {
        return defaultMessage;
    }
}
