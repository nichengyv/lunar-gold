package com.capsule.agentkey.filter;

import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.util.JwtUtil;
import com.capsule.agentkey.util.ResponseWriter;
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
    private final com.capsule.agentkey.service.TokenSessionService tokenSessionService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil,
                                   com.capsule.agentkey.service.TokenSessionService tokenSessionService) {
        this.jwtUtil = jwtUtil;
        this.tokenSessionService = tokenSessionService;
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
                //利用token解密从jwt来获取信息，这里获取userid
                Long userId = jwtUtil.getUserIdFromToken(token);
                String jti = jwtUtil.getJtiFromToken(token);

                //可撤销校验：jti 必须在 Redis 白名单里（登出/改密码会剔除）
                //无 jti 的老 token 直接拒绝——撤销机制上线后不应再放行无身份编号的会话
                if (jti == null || !tokenSessionService.isActive(jti)) {
                    log.warn("JWT 已撤销或不在白名单 [userId={}]", userId);
                    ResponseWriter.writeError(response, ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
                    return;
                }

                //把用户id塞进Spring Security上下文，相当于告诉Spring本次请求已登录，用户id是这个
                //第一段先包装authentication对象，第二段写入security上下文
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, new ArrayList<>());//（principal，credential凭证（但是jwt场景不需要密码），权限集合）
                //把 jti 挂到 details 上，Controller 登出时可直接取用，无需重新解析 token
                auth.setDetails(jti);
                //SecurityContextHolder.getContext()设置当前线程上下文盒子， setAuthentication把token放进上下文
                SecurityContextHolder.getContext().setAuthentication(auth);//放进上下文
            }
            //验证失败
            catch (Exception e) {
                log.warn("JWT 校验失败: {}", e.getMessage());
                //401 = 未携带或无效凭证。这里在 Filter 层，@RestControllerAdvice 覆盖不到，
                //所以借 ResponseWriter 输出与全局信封一致的结构（原先手写 JSON 拼接，message 含引号即坏）
                ResponseWriter.writeError(response, ErrorCode.UNAUTHORIZED, "无效的令牌");
                return;//直接返回，不放行
            }
        }
        //放行到下一个过滤器
        chain.doFilter(request, response);
    }
}
