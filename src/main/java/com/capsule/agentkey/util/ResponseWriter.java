package com.capsule.agentkey.util;

import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 在 Filter / Interceptor 等无法被 @RestControllerAdvice 覆盖的位置，
 * 统一输出与 ApiResponse 结构一致的 JSON 错误响应。
 */
public final class ResponseWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponseWriter() {
    }

    public static void writeError(HttpServletResponse response, ErrorCode errorCode, String message)
            throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String msg = (message == null || message.isBlank()) ? errorCode.getDefaultMessage() : message;
        response.getWriter().write(MAPPER.writeValueAsString(ApiResponse.fail(errorCode, msg)));
    }
}