package com.culture.config;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 基础安全响应头
 *
 * <p>只加「不会影响现有页面」的四项：禁止 MIME 嗅探、Referrer 策略、禁止被 iframe 嵌套、
 * 关闭不需要的浏览器特性权限。<b>刻意不加 CSP</b>：后台入口有内联脚本（原生 Symbol 保护），
 * 加了会直接把页面打挂。HSTS 需要 HTTPS，交给生产 Nginx。</p>
 *
 * <p>通过 {@code setHeader} 写入，避免与其它组件设置出两个同名头（多个值会让浏览器按最严格解析）。</p>
 */
@Component
@Order(Integer.MIN_VALUE + 100)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!response.containsHeader("X-Content-Type-Options")) {
            response.setHeader("X-Content-Type-Options", "nosniff");
        }
        if (!response.containsHeader("Referrer-Policy")) {
            response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        }
        if (!response.containsHeader("X-Frame-Options")) {
            response.setHeader("X-Frame-Options", "SAMEORIGIN");
        }
        if (!response.containsHeader("Permissions-Policy")) {
            response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        }
        chain.doFilter(request, response);
    }
}
