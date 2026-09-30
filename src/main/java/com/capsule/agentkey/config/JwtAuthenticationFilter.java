package com.capsule.agentkey.config;

import com.capsule.agentkey.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

//验证JWT令牌，每次请求先走这个过滤器，比controller更早（因为继承了OncePerRequestFilter）
//配合securityconfig
//从 Header 取出 Token，解析出 userId，存到 Spring Security 上下文
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    //
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");//从请求头取出Authorization的值
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            //去掉“Bear前缀”
            String token = authHeader.substring(7);
            try {
                //利用token解密来获取信息，这里获取userid
                Long userId = jwtUtil.getUserIdFromToken(token);

                //把用户id塞进Spring Security上下文，相当于告诉Spring本次请求已登录，用户id是这个
                //第一行先把信息打包，第二行入上下文
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, new ArrayList<>());
                //SecurityContextHolder.getContext()设置当前线程上下文盒子， setAuthentication把token放进上下文
                SecurityContextHolder.getContext().setAuthentication(auth);//放进上下文
            }
            //验证失败
            catch (Exception e) {
                log.warn("JWT 校验失败: {}", e.getMessage());
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"无效的令牌\",\"data\":null,\"timestamp\":" + System.currentTimeMillis() + "}");
                return;//直接返回，不放行
            }
        }
        //放行到下一个过滤器
        chain.doFilter(request, response);
    }
}
