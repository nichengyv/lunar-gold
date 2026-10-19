package com.capsule.agentkey.exception;

/**
 * 业务异常：只携带 ErrorCode，自身不存 HTTP 状态码。
 *
 * 抛出方只表达"哪里错了、给人看什么话"，"该返回几"由 ErrorCode 决定，
 * 再由 GlobalExceptionHandler 统一翻译成响应，避免各处自己拍脑袋写状态码。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 默认归为业务处理失败（400）——保持与既有行为一致 */
    public BusinessException(String message) {
        this(ErrorCode.BUSINESS_ERROR, message);
    }

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
