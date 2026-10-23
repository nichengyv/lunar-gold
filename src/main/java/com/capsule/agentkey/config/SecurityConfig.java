package com.capsule.agentkey.config;

import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.filter.JwtAuthenticationFilter;
import com.capsule.agentkey.util.ResponseWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
//权限控制
//完成jwt认证
//这个类配置了：哪些接口需要登录，哪些接口不需要令牌，安排过滤器（如JwtAuthenticationFilter）执行顺序
//
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${app.cors.allowed-origins:http://localhost:8080,http://127.0.0.1:8080}")
    private String allowedOrigins;

    //CSP 的 connect-src：允许前端 fetch 的目标，部署时通过 CSP_CONNECT_SRC 覆盖，不必改代码
    @Value("${app.security.csp-connect-src:http://localhost:8080 http://127.0.0.1:8080}")
    private String cspConnectSrc;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    //BCryptPasswordEncoder()，密码哈希加密工具
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    //安全过滤主链
    //用来配置整个后端接口的权限规则，跨域，会话策略，过滤器顺序
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        //关闭CSRF防护
        http.csrf(csrf -> csrf.disable())

                //开启跨域，使用项目自定义跨域配置，允许前端访问后端接口
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                //STATELESS表示无会话模式，不创建服务器Session，放弃服务器记忆用户
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                //配置不需要认证的路由
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/**",
                                "/api/public-key",
                                "/",
                                "/index.html",
                                "/static/**",
                                "/css/**",
                                "/js/**",
                                "/favicon.ico",
                                "/api/agent/test-stream",
                                "/actuator/health",
                                //Prometheus 采集端不带 JWT，需放行；生产务必用网络策略/反代只允许采集器访问
                                "/actuator/prometheus",
                                //接口文档（springdoc）：生产通过 SPRINGDOC_ENABLED=false 整体关闭
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        )
                        //白名单接口，不需要登录，不需要token，任何人可以访问
                        .permitAll()
                        //指定接口必须携带有效JWT，token，完成身份验证
                        .requestMatchers("/api/agent/chat/stream", "/api/knowledge/**").authenticated()//这里看起来有点多此一举，先不删，后期可能需要不一样的权限
                        //其他没有进白名单的接口都需要认证
                        .anyRequest().authenticated()
                )
                //未认证（无 token / token 过期）统一返回 401 信封，与前端 api.js 的 401 跳转约定闭环。
                //不配置此项时 Spring 默认返回 403，前端等不到 401，登录过期后用户会卡死在当前页。
                //403 只保留给"已认证但无权限"的场景，符合 RFC 7235 语义
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (request, response, authException) ->
                                ResponseWriter.writeError(response, ErrorCode.UNAUTHORIZED, null)))
                //内容安全策略，浏览器的安全防护，限制网页可以加载哪些外部资源
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; " +
                                        "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; " +
                                        "style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; " +
                                        "font-src 'self' https://cdnjs.cloudflare.com; " +
                                        "connect-src 'self' " + cspConnectSrc + " https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; " +
                                        "img-src 'self' data: https:;"
                        ))
                )
                //把自己写的jwtAuthenticationFilter插到Spring内置过滤器之前
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        //构建并返回完整的安全过滤链
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = List.of(allowedOrigins.split(","));
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}