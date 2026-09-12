package com.culture.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.culture.api.JwtAuthFilter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
@EnableWebSecurity //拦截所有请求 AOP拦截器
@EnableGlobalMethodSecurity(prePostEnabled = true)//开启细粒度控制 判断用户对某个控制层的方法是否具有访问权限 @PreAuthorize
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {

    @Qualifier("userDetailsServiceImpl")
    @Autowired
    UserDetailsService userDetailsService;

    @Autowired
    JwtAuthFilter jwtAuthFilter;


    //授权
    //配置拦截资源 首页所有人可以访问 功能页只有对应有权限的人才能访问 链式编程
    @Override
    protected void configure(HttpSecurity http) throws Exception {

        // 前后端分离：/api/** 先走 JWT 过滤器（校验通过后写入 SecurityContext 并桥接 Session）
        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        //====================================================================
        // 请求规则（前后端分离：后端只提供 REST API 与后端托管的静态资源）
        //   - 公开：公开 API、认证 API、Swagger、Druid、设计资源、上传媒体
        //   - 其余一律要求已认证（/api/** 由 JwtAuthFilter 用前后台双 Token 鉴权）
        //====================================================================
        http.authorizeRequests()
                .antMatchers(
                        // ---- 文档与监控 ----
                        "/swagger-ui.html", "/swagger-ui.html/**", "/swagger-ui.html/*",
                        "/swagger-resources", "/swagger-resources/**",
                        "/webjars/**", "/webjars/springfox-swagger-ui/**",
                        "/v2/api-docs", "/META-INF/resources/webjars/**",
                        "/druid", "/druid/**", "/druid/*", "/druid/login.html",
                        // ---- 前端设计资源与媒体（由 Nginx 反代到本服务）----
                        "/static/**", "/index/**",
                        "/upload/media/**",            // 富文本正文图片/视频
                        "/showFmImg/**", "/showimage/**",
                        // ---- SEO：站点地图 / 爬虫规则 / RSS（爬虫无登录态，必须放行）----
                        "/sitemap.xml", "/robots.txt", "/rss.xml",
                        // sitemap 分片（规范路径形式）；索引默认用 ?shard= 查询形式，
                        // 补上这两条后把 app.site.sitemap.shard-url-mode 设为 path 即可切换
                        "/sitemap-static.xml", "/sitemap-culture-*.xml",
                        // ---- 公开 API 与认证 API ----
                        "/api/auth/**",
                        "/api/home/**",
                        "/api/culture/list",
                        "/api/culture/detail",
                        "/api/culture/categorys",
                        "/api/sentence/list",
                        "/api/search",                 // 全站搜索（前台公开，无需登录）
                        // 热门搜索词也公开；注意**不要**写成 /api/search/** ——
                        // 那会让 /api/search/history（需登录）变成匿名可达，丢掉 401 保护
                        "/api/search/hot",
                        "/api/tag/list",               // 标签列表（前台公开）
                        "/api/tag/cultures",           // 某标签下的文化分页（前台公开）
                        "/api/seo/**",                 // 页面 meta（OG/JSON-LD，爬虫与预渲染工具要读）
                        "/api/comment/list",           // 评论列表（公开只读，仅返回已通过）；提交需登录
                        "/api/health",                 // 健康检查（探针用）
                        "/api/metrics",                // Prometheus 指标（监控抓取）
                        "/api/client-error")           // 前端错误上报（匿名）
                .permitAll()
                .anyRequest().authenticated();

        // 说明：管理端登录页已迁到 Vue（/admin/login），不再使用 Spring 表单登录；
        // 后台登录接口会写入 Spring Security 会话，供 Excel 导出等浏览器直接下载的
        // 后端端点使用（其余接口一律走前后台 Token）。
        http.csrf().disable();
        http.headers()
                // 关闭 X-Content-Type-Options:nosniff ，使 Druid 页面可以正常显示
                .contentTypeOptions().disable();

        // 未认证 / 无权限统一返回 JSON（前端按 401 清理 Token 并跳登录页）
        http.exceptionHandling()
                .authenticationEntryPoint((req, resp, ex) -> writeJson(resp, 401, "未登录或凭证无效"))
                .accessDeniedHandler((req, resp, ex) -> writeJson(resp, 403, "无权限访问该资源"));
    }

    /** 统一的 JSON 错误输出 */
    private void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
    }

    //认证
    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.userDetailsService(userDetailsService).passwordEncoder(new BCryptPasswordEncoder());
    }

//    $2a$10$YITYi7HjqT2gh8jEF6eyquR/Og0qmYBNT8cQLaEjjS92jcZHwsI9G
//    $2a BCrypt算法版本  $10 算法强度  $YITYi7HjqT2gh8jEF6eyquR 随机生成盐  Og0qmYBNT8cQLaEjjS92jcZHwsI9G hash值
//    public static void main(String[] args) {
//        BCryptPasswordEncoder bCryptPasswordEncoder = new BCryptPasswordEncoder();
//        String encode = bCryptPasswordEncoder.encode("123");
//        System.out.println(encode);
//    }

}

