package com.culture.config;

import com.culture.api.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Spring Security 配置（Spring Boot 3 / Spring Security 6）。
 *
 * <p><b>本次升级的关键改写：</b>Security 6 已<b>删除</b>
 * {@code WebSecurityConfigurerAdapter}，配置方式从「继承并覆写 configure()」
 * 改为「声明 {@link SecurityFilterChain} Bean」。同时：</p>
 * <ul>
 *   <li>{@code antMatchers} → {@code requestMatchers}；</li>
 *   <li>{@code @EnableGlobalMethodSecurity} → 已移除。项目里<b>没有任何</b>
 *       {@code @PreAuthorize} / {@code @Secured}，开启细粒度方法级授权属于无效配置
 *       （真正的后台鉴权由 {@link JwtAuthFilter} 的作用域判断 + 各控制器
 *       自行调用 {@code isAdmin()} 完成）；</li>
 *   <li>{@code AuthenticationManagerBuilder} 配置块已移除：登录由
 *       {@code AuthService.login()} 自行校验密码，不经过 DaoAuthenticationProvider；</li>
 *   <li>开启 {@link SessionCreationPolicy#STATELESS}：这是「纯 Token 无状态」的落地，
 *       配合认证接口不再写 Session，服务端不会再为每个请求创建会话。</li>
 * </ul>
 *
 * <p><b>可以继续禁用 CSRF 的原因：</b>改造后服务端不再使用任何 Cookie 会话
 * （没有 Session、没有表单登录），浏览器的跨站请求带不上 Authorization 头，
 * CSRF 攻击面消失。此前「禁用 CSRF + 同时使用 Cookie 会话」才是自相矛盾的。</p>
 *
 * <p><b>本次同时收紧了公开路径：</b>Druid 监控页（{@code /druid/**}）与
 * Swagger（{@code /swagger-ui.html}、{@code /v2/api-docs}、{@code /swagger-resources}）
 * 的依赖已删除，对应的 permitAll 条目一并移除，不再对外暴露。
 * 也正因为不再需要 Druid 页面，原先为了让它正常显示而全局关闭的
 * {@code X-Content-Type-Options: nosniff} 已恢复为 Spring Security 的默认开启状态。</p>
 */
@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

    /**
     * 无需登录即可访问的路径。
     *
     * <p>注意 {@code /api/search} 与 {@code /api/search/hot} 的写法差异：
     * 不能写成 {@code /api/search/**} —— 那会让需要登录的 {@code /api/search/history}
     * 变成匿名可达。</p>
     */
    private static final String[] PUBLIC_PATHS = {
            // ---- 前端设计资源与媒体（/index、/static 已由 Nginx 托管，此处仅为本地预览兜底）----
            "/static/**", "/index/**",
            "/upload/media/**",            // 富文本正文图片/视频
            "/showFmImg/**", "/showimage/**",
            // ---- SEO：站点地图 / 爬虫规则 / RSS（爬虫无登录态）----
            "/sitemap.xml", "/robots.txt", "/rss.xml",
            "/sitemap-static.xml", "/sitemap-culture-*.xml",
            // ---- 公开 API 与认证 API ----
            "/api/auth/**",
            "/api/home/**",
            "/api/culture/list",
            "/api/culture/detail",
            "/api/culture/categorys",
            "/api/sentence/list",
            "/api/search",
            "/api/search/hot",
            "/api/tag/list",
            "/api/tag/cultures",
            "/api/seo/**",
            "/api/comment/list",
            "/api/health",
            "/api/metrics",
            "/api/client-error"
    };

    private final JwtAuthFilter jwtAuthFilter;

    public WebSecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // /api/** 先走 JWT 过滤器：校验通过后写入 SecurityContext（不再桥接 Session）
        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .anyRequest().authenticated());

        // 纯 Token 无状态：不创建也不使用 HttpSession
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // 无 Cookie 会话 → CSRF 无攻击面；同时关掉表单登录/HTTP Basic/默认登出页
        http.csrf(AbstractHttpConfigurer::disable);
        http.formLogin(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);

        // 未认证 / 无权限统一返回 JSON（前端按 401 清理 Token 并跳登录页）
        http.exceptionHandling(e -> e
                .authenticationEntryPoint((req, resp, ex) -> writeJson(resp, 401, "未登录或凭证无效"))
                .accessDeniedHandler((req, resp, ex) -> writeJson(resp, 403, "无权限访问该资源")));

        return http.build();
    }

    /**
     * 统一的 JSON 错误输出。
     *
     * <p>用 {@link com.fasterxml.jackson.databind.ObjectMapper} 序列化而不是手工拼字符串：
     * 拼接方式在 message 含引号/换行/反斜杠时会产出非法 JSON
     * （当前文案是常量所以暂时不会出问题，但这是一条随时会被踩中的注入式缺陷）。</p>
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", status);
        body.put("message", message);
        body.put("data", null);
        MAPPER.writeValue(response.getWriter(), body);
    }
}
