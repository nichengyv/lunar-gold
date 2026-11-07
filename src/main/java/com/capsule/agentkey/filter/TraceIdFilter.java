package com.capsule.agentkey.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * B5：为每个请求生成 16 位 traceId 并写入 MDC。
 * 日志 pattern 中的 %X{traceId} 由此填充——之前日志里永远为空的 [] 就是这个占位符没数据。
 * 作用：一次请求产生的所有日志（含异步线程外透传）可用 traceId 串联，排障时 grep 一次就能还原全链路。
 *
 * 注意：响应头同时回写 X-Trace-Id，前端/网关报障时把它发回来即可定位日志。
 * 顺序用最高优先级，保证在 JwtAuthenticationFilter 之前放入 MDC（其后所有日志都带 traceId）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        //优先复用上游（网关/负载均衡）传来的 traceId，没有才自己生成
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank() || traceId.length() > 32) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put("traceId", traceId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            //Tomcat 线程池复用，必须清理，否则下一个请求会串到上一个请求的 traceId
            MDC.clear();
        }
    }
}
