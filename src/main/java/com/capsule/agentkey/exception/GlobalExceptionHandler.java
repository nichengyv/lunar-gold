package com.capsule.agentkey.exception;

import com.capsule.agentkey.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器：把异常统一翻译成"HTTP 状态 + 统一信封"。
 *
 * 职责边界：Controller / Service 只负责抛异常，不决定状态码；
 * 状态码的唯一来源是 ErrorCode，因此本类内不出现 400/403/500 等字面量。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(ErrorCode.PARAM_INVALID, msg);
    }

    /**
     * 请求体无法解析：JSON 语法错误、字段类型不匹配、body 为空。
     * 这类问题属于客户端输入错误，应答 400；
     * 此前没有对应处理器，异常落到 Exception 兜底分支变成 500「系统繁忙」，
     * 把可预期的输入错误混进了服务端故障告警。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return build(ErrorCode.PARAM_INVALID, "请求内容格式不正确，请检查后重试");
    }

    /**
     * 路径变量 / 查询参数类型不匹配，例如 /documents/abc（期望 Long）。
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> handleParamMismatch(Exception e) {
        log.warn("请求参数格式不匹配: {}", e.getMessage());
        return build(ErrorCode.PARAM_INVALID, "请求参数格式不正确");
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        //状态码来自异常自带的 ErrorCode，而不是固定 400，业务语义得以保留
        return build(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    public ResponseEntity<ApiResponse<Void>> handleAuth(Exception e) {
        log.warn("权限异常: {}", e.getMessage());
        return build(ErrorCode.FORBIDDEN, "无权访问");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return build(ErrorCode.NOT_FOUND, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("系统异常", e);
        return build(ErrorCode.INTERNAL_ERROR, null);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(MultipartException e) {
        log.warn("文件上传协议异常: {}", e.getMessage());
        return build(ErrorCode.UPLOAD_PROTOCOL_ERROR, "文件上传格式错误或文件过大");
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Void>> handleIOException(IOException e) {
        //异常细节只进日志，不回给前端：路径、权限等信息对用户无意义且属内部实现
        log.error("IO 异常", e);
        return build(ErrorCode.IO_ERROR, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxSize(MaxUploadSizeExceededException e) {
        log.warn("文件大小超过限制: {}", e.getMessage());
        return build(ErrorCode.PAYLOAD_TOO_LARGE, "文件过大，请上传不超过 20MB 的文件");
    }

    /**
     * 本类唯一的响应构造出口。
     * HTTP 状态与响应体 code 都取自同一个 ErrorCode，机制上保证二者不可能不一致；
     * message 为 null 时回退到错误码默认文案。
     */
    private ResponseEntity<ApiResponse<Void>> build(ErrorCode errorCode, String message) {
        String msg = (message == null || message.isBlank()) ? errorCode.getDefaultMessage() : message;
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode, msg));
    }
}
