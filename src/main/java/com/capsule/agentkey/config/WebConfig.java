package com.capsule.agentkey.config;

import com.capsule.agentkey.interceptor.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/agent/chat/stream")
                //A7 防爆破：登录/注册等未认证接口纳入 IP 级限流（拦截器内已回退到按 IP 计数）
                .addPathPatterns("/api/auth/**")
                //public-key 是公开静态资源，且前端每次登录/注册提交前都会取一次，
                //限流它会误伤登录流程（表现为"获取公钥失败"）；爆破防护由 login/register 限流 + 失败锁定承担
                .excludePathPatterns("/api/auth/public-key");
    }

    /**
     * 后端托管前端构建产物（P3-01 前置）。
     * <p>
     * 打包时通过 Maven profile {@code with-frontend} 把 {@code frontend/dist} 拷进
     * {@code classpath:/static}，桌面壳只需加载 {@code http://127.0.0.1:<port>/}，
     * 不必再单独起静态服务器或处理 file:// 跨域。
     * <p>
     * 未打包前端时 {@code static/index.html} 不存在，除真实文件外一律 404，
     * 与原行为一致（dev 用 Vite、cloud 用 Nginx 托管）。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(
                        "classpath:/META-INF/resources/",
                        "classpath:/resources/",
                        "classpath:/static/",
                        "classpath:/public/")
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    /**
     * 命中真实文件则照常返回；前端路由（无扩展名）交给 index.html；
     * 接口与文档路径保持原语义，不返回 HTML。
     */
    private static class SpaFallbackResolver extends PathResourceResolver {

        private static final String[] BACKEND_PREFIXES = {
                "api/", "actuator/", "v3/", "swagger-ui", "error"
        };

        private final Resource index = new ClassPathResource("static/index.html");

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource resolved = super.getResource(resourcePath, location);
            if (resolved != null) {
                return resolved;
            }
            for (String prefix : BACKEND_PREFIXES) {
                if (resourcePath.startsWith(prefix)) {
                    return null;
                }
            }
            // 带扩展名说明是缺失的静态资源，按 404 处理，避免把 JS/CSS 请求回成 HTML
            String lastSegment = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            if (lastSegment.contains(".")) {
                return null;
            }
            return index.exists() ? index : null;
        }
    }
}
