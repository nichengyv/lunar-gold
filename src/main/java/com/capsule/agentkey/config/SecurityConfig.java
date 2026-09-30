package com.capsule.agentkey.config;

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
                                "/actuator/health"
                        )
                        //白名单接口，不需要登录，不需要token，任何人可以访问
                        .permitAll()
                        //指定接口必须携带有效JWT，token，完成身份验证
                        .requestMatchers("/api/agent/chat/stream", "/api/knowledge/**").authenticated()//这里看起来有点多此一举，先不删，后期可能需要不一样的权限
                        //其他没有进白名单的接口都需要认证
                        .anyRequest().authenticated()
                )
                //内容安全策略，浏览器的安全防护，限制网页可以加载哪些外部资源
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; " +
                                        "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; " +
                                        "style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; " +
                                        "font-src 'self' https://cdnjs.cloudflare.com; " +
                                        "connect-src 'self' http://localhost:8080 http://127.0.0.1:8080 https://api.deepseek.com https://api.tavily.com https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; " +
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