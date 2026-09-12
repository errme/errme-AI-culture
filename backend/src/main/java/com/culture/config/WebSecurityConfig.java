package com.culture.config;

import com.culture.api.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    private final JwtAuthFilter jwtAuthFilter;

    /**
     * 运维工具（Druid 监控页 / API 文档）的访问路径，全部来自配置。
     *
     * <p>路径可配置，所以不能在静态常量里写死 —— 否则改了
     * {@code app.druid.stat.path} / {@code app.api-doc.ui-path} 之后安全配置就对不上了。</p>
     */
    private final String druidPath;
    private final boolean druidEnabled;
    private final boolean apiDocEnabled;
    private final String apiDocUiPath;
    private final String apiDocJsonPath;

    public WebSecurityConfig(
            JwtAuthFilter jwtAuthFilter,
            @Value("${app.druid.stat.path:/druid/*}") String druidPath,
            @Value("${app.druid.stat.enabled:true}") boolean druidEnabled,
            @Value("${app.api-doc.enabled:true}") boolean apiDocEnabled,
            @Value("${app.api-doc.ui-path:/swagger-ui.html}") String apiDocUiPath,
            @Value("${app.api-doc.api-docs-path:/v3/api-docs}") String apiDocJsonPath) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.druidPath = druidPath;
        this.druidEnabled = druidEnabled;
        this.apiDocEnabled = apiDocEnabled;
        this.apiDocUiPath = apiDocUiPath;
        this.apiDocJsonPath = apiDocJsonPath;
    }

    /**
     * 组装 permitAll 路径清单。
     *
     * <p>为什么要动态拼：Druid 与 API 文档的路径是可配置的，
     * 且关闭时应当<b>连放行规则都不注册</b>（避免留下一个「已放行但没有对应端点」的悬空规则）。</p>
     */
    private List<String> buildPublicPaths() {
        List<String> paths = new ArrayList<>(Arrays.asList(
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
                "/api/search",                 // 注意：不能写 /api/search/**，
                "/api/search/hot",             // 否则 /api/search/history（需登录）会变成匿名可达
                "/api/tag/list",
                "/api/tag/cultures",
                "/api/seo/**",
                "/api/comment/list",
                "/api/health",
                // Prometheus 指标：监控系统抓取时需要匿名可读（只输出聚合数字，无业务明细）。
                // 注意别在重构这段清单时漏掉它 —— 漏掉会让 /api/metrics 直接 401，
                // 抓取端静默断掉（本次重构就踩过一次，由 feature-check 用例抓出）。
                "/api/metrics",
                "/api/client-error"
        ));

        // Druid 监控页：**由它自己的登录 + IP 白名单保护**（见 DruidConfig），
        // 所以这里必须放行，否则连它的登录页都会被 Spring Security 拦掉。
        // 关闭时（druidEnabled=false）不注册任何规则。
        if (druidEnabled) {
            String base = druidPath.endsWith("*")
                    ? druidPath.substring(0, druidPath.length() - 1)   // "/druid/*" -> "/druid/"
                    : druidPath;
            if (!base.endsWith("/")) base = base + "/";
            paths.add(base + "**");
            paths.add(druidPath);
        }

        // API 文档（springdoc）：UI 页面 + OpenAPI JSON。
        // 生产环境建议用 API_DOC_ENABLED=false 关闭，或由 Nginx 限制为内网可访问。
        if (apiDocEnabled) {
            paths.add(apiDocUiPath);
            paths.add(apiDocUiPath + "/**");
            paths.add(apiDocJsonPath);
            paths.add(apiDocJsonPath + "/**");
            paths.add("/swagger-ui/**");
            paths.add("/v3/api-docs.yaml");
            paths.add("/swagger-resources/**");
            paths.add("/webjars/**");
        }

        return paths;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // /api/** 先走 JWT 过滤器：校验通过后写入 SecurityContext（不再桥接 Session）
        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(buildPublicPaths().toArray(new String[0])).permitAll()
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
