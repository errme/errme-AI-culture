package com.culture.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 全局配置：
 * 1) CORS：只允许配置的前端来源调用 /api/**（app.cors.allowed-origins，逗号分隔）；
 * 2) 内容协商固定为 JSON：忽略 Accept 头，避免浏览器原生表单（Accept: text/html）
 *    请求 /api/** 时返回 406 HTML 错误页（登录/注册"服务器响应异常"的根因）。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 允许跨域访问的前端来源（逗号分隔）。
     * 默认只放行本机开发用的两个端口；生产请在 application.yml 或环境变量
     * APP_CORS_ALLOWED_ORIGINS 里配置真实域名，例如：
     *   app.cors.allowed-origins: https://culture.example.com
     */
    @Value("${app.cors.allowed-origins:http://localhost:8080,http://127.0.0.1:8080,http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.split(",");
        for (int i = 0; i < origins.length; i++) origins[i] = origins[i].trim();
        registry.addMapping("/api/**")
                .allowedOrigins(origins)          // 精确白名单（不再用 * + allowCredentials 的危险组合）
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        // 忽略 Accept 头、默认 application/json：@ResponseBody 一律输出 JSON
        configurer.ignoreAcceptHeader(true);
        configurer.defaultContentType(MediaType.APPLICATION_JSON);
    }
}
