package com.capsule.agentkey.config;

import com.capsule.agentkey.interceptor.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
}
