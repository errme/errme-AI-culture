package com.culture.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册接口限流拦截器。
 *
 * 只拦截 API（不拦静态资源），路径白名单见 {@link RateLimitInterceptor#LIMITS}。
 * 注册方式与 OperationLogWebConfig 保持一致（同一个 WebMvcConfigurer 体系）。
 */
@Configuration
public class RateLimitWebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    public RateLimitWebConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/health");   // 健康检查不能被限流（探针会频繁调用）
    }
}
