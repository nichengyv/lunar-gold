package com.capsule.agentkey.config;

import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.filter.ClientTokenFilter;
import com.capsule.agentkey.filter.JwtAuthenticationFilter;
import com.capsule.agentkey.filter.LocalTokenFilter;
import com.capsule.agentkey.filter.LoopbackGuardFilter;
import com.capsule.agentkey.filter.ScopeAuthorizationFilter;
import com.capsule.agentkey.util.ResponseWriter;
import jakarta.servlet.Filter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
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
    // 云端用 JWT、桌面端用本地令牌：两个过滤器都带 @Profile，用 ObjectProvider 取实际装配的那个
    private final ObjectProvider<JwtAuthenticationFilter> jwtFilterProvider;
    private final ObjectProvider<LocalTokenFilter> localTokenFilterProvider;
    /** P6-01：客户端令牌过滤器，仅 desktop profile 存在 */
    private final ObjectProvider<ClientTokenFilter> clientTokenFilterProvider;
    /** P6-01：客户端请求的回环边界校验与 scope 校验，均仅 desktop profile 存在 */
    private final ObjectProvider<LoopbackGuardFilter> loopbackGuardFilterProvider;
    private final ObjectProvider<ScopeAuthorizationFilter> scopeAuthorizationFilterProvider;

    @Value("${app.cors.allowed-origins:http://localhost:8080,http://127.0.0.1:8080}")
    private String allowedOrigins;

    //CSP 的 connect-src：允许前端 fetch 的目标，部署时通过 CSP_CONNECT_SRC 覆盖，不必改代码
    @Value("${app.security.csp-connect-src:http://localhost:8080 http://127.0.0.1:8080}")
    private String cspConnectSrc;

    public SecurityConfig(ObjectProvider<JwtAuthenticationFilter> jwtFilterProvider,
                          ObjectProvider<LocalTokenFilter> localTokenFilterProvider,
                          ObjectProvider<ClientTokenFilter> clientTokenFilterProvider,
                          ObjectProvider<LoopbackGuardFilter> loopbackGuardFilterProvider,
                          ObjectProvider<ScopeAuthorizationFilter> scopeAuthorizationFilterProvider) {
        this.jwtFilterProvider = jwtFilterProvider;
        this.localTokenFilterProvider = localTokenFilterProvider;
        this.clientTokenFilterProvider = clientTokenFilterProvider;
        this.loopbackGuardFilterProvider = loopbackGuardFilterProvider;
        this.scopeAuthorizationFilterProvider = scopeAuthorizationFilterProvider;
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
                        // SSE / Async：Tomcat 在 SseEmitter 异步写入时会以 ASYNC dispatch 重跑 filter chain，
                        // 此时原始 REQUEST 已完成、SecurityContext 可能已被清理。原始 REQUEST 已完成过认证，
                        // ASYNC / FORWARD dispatch 不再重复鉴权，避免 FilterSecurityInterceptor 因 SecurityContext 缺失抛 AccessDenied。
                        // FORWARD 也放行为 `/error` dispatch 会走 FORWARD（或 ERROR dispatch），SSE 期间 response already committed 时 Tomcat 会触发。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                        .requestMatchers(
                                "/error",
                                "/api/auth/**",
                                "/api/public-key",
                                "/api/meta",
                                // P5-01：提示词热重载，控制器内部再校验回环地址
                                "/api/admin/prompts/**",
                                // 桌面端本地令牌引导接口：仅回环地址 + 来源白名单，见 DesktopTokenController
                                "/api/desktop/token",
                                "/",
                                "/index.html",
                                // SPA 路由：服务端只负责回 index.html，页面权限由前端路由守卫与接口鉴权决定
                                "/chat",
                                "/knowledge",
                                "/keys",
                                "/profile",
                                "/settings",
                                "/login",
                                "/download",
                                "/static/**",
                                // Vite 生产构建把脚本与样式放在 /assets 下
                                "/assets/**",
                                "/css/**",
                                "/js/**",
                                "/favicon.ico",
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
                );
        // 只装配当前 profile 对应的认证过滤器：
        // 云端 = JWT（含 Redis 撤销白名单），桌面端 = 本地令牌，二者不会同时存在
        JwtAuthenticationFilter jwtFilter = jwtFilterProvider.getIfAvailable();
        if (jwtFilter != null) {
            http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        }
        LocalTokenFilter localTokenFilter = localTokenFilterProvider.getIfAvailable();
        if (localTokenFilter != null) {
            http.addFilterBefore(localTokenFilter, UsernamePasswordAuthenticationFilter.class);
        }
        // P6-01：客户端令牌与本地令牌同走 Authorization，必须先注册、先执行；
        // 锚点用 LocalTokenFilter 而不是同一个 UsernamePasswordAuthenticationFilter，
        // 避免依赖同序过滤器的插入顺序
        ClientTokenFilter clientTokenFilter = clientTokenFilterProvider.getIfAvailable();
        if (clientTokenFilter != null) {
            http.addFilterBefore(clientTokenFilter,
                    localTokenFilter != null ? LocalTokenFilter.class : UsernamePasswordAuthenticationFilter.class);
        }
        // P6-01：认证之后依次做回环边界校验、scope 校验；
        // 两者都只对 ClientPrincipal 生效，Web / 云端请求直接放行
        Class<? extends Filter> anchor =
                localTokenFilter != null ? LocalTokenFilter.class : UsernamePasswordAuthenticationFilter.class;
        LoopbackGuardFilter loopbackGuardFilter = loopbackGuardFilterProvider.getIfAvailable();
        if (loopbackGuardFilter != null) {
            http.addFilterAfter(loopbackGuardFilter, anchor);
            anchor = LoopbackGuardFilter.class;
        }
        ScopeAuthorizationFilter scopeAuthorizationFilter = scopeAuthorizationFilterProvider.getIfAvailable();
        if (scopeAuthorizationFilter != null) {
            http.addFilterAfter(scopeAuthorizationFilter, anchor);
        }
        //构建并返回完整的安全过滤链
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = List.of(allowedOrigins.split(","));
        config.setAllowedOrigins(origins);
        // 必须包含 PATCH：会话头改权限、工作区默认权限、重命名会话都走 PATCH，
        // 漏掉时浏览器预检直接失败，前端只会看到"网络不稳定"
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}