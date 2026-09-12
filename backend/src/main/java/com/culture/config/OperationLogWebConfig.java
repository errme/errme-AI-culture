package com.culture.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 操作日志拦截器注册（独立的 WebMvcConfigurer，不改动 MyPicConfig / CorsConfig）。
 * 只拦后台管理路径；具体是否记录由 OperationLogInterceptor 内部判断（GET 不记录）。
 */
@Configuration
public class OperationLogWebConfig implements WebMvcConfigurer {

    @Autowired
    private OperationLogInterceptor operationLogInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(operationLogInterceptor)
                .addPathPatterns("/api/admin/**");
    }
}
