package com.culture.api;

import com.culture.auth.service.JwtService;
import com.culture.auth.service.JwtService.Scope;
import com.culture.entity.User;
import com.culture.service.UserService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

/**
 * JWT 认证过滤器（前后台令牌分离）。
 *
 * <p>鉴权矩阵：</p>
 * <table border="1">
 *   <tr><th>路径</th><th>要求</th></tr>
 *   <tr><td>/api/admin/**</td><td><b>后台令牌</b>（scope=admin）或后台 Session 管理员；前台令牌访问返回 403</td></tr>
 *   <tr><td>/api/user/**、/api/culture/like|cancel</td><td><b>前台令牌</b>（scope=front）或已登录 Session</td></tr>
 *   <tr><td>其余 /api/**（非公开、非 auth）</td><td>任意一种令牌或 Session 登录</td></tr>
 * </table>
 *
 * <p>前台令牌只授予最小权限（ROLE_FRONT_USER），不会写入 Spring Security 会话，
 * 因此前台登录<strong>不能</strong>进入后台管理页面；后台令牌会建立完整的管理员认证并桥接 Session。</p>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 请求属性：当前登录用户ID（各 Controller 通过它取登录人） */
    public static final String ATTR_LOGIN_USER_ID = "loginUserId";
    /** 请求属性：本次请求的令牌作用域（front / admin / session） */
    public static final String ATTR_TOKEN_SCOPE = "tokenScope";

    public static final String ROLE_ADMIN_NAME = "管理员";

    private final JwtService jwtService;
    private final UserService userService;

    public JwtAuthFilter(JwtService jwtService, UserService userService) {
        this.jwtService = jwtService;
        this.userService = userService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 仅放行 OPTIONS 预检（CORS 处理）
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    /** 公开接口：无需登录即可访问 */
    private boolean isPublic(String path) {
        return path.startsWith("/api/home")
                || path.startsWith("/api/culture/list")
                || path.startsWith("/api/culture/detail")
                || path.startsWith("/api/culture/categorys")
                || path.startsWith("/api/sentence/")
                || path.startsWith("/api/search")     // 全站搜索（前台公开）
                || path.startsWith("/api/tag/list")        // 标签列表（前台公开）
                || path.startsWith("/api/tag/cultures")    // 某标签下的文化分页（前台公开）
                || path.startsWith("/api/seo")             // 页面 meta（OG/JSON-LD，爬虫与预渲染工具要读）
                || path.startsWith("/api/comment/list")   // 评论列表（公开只读）；提交需登录，故不在此列
                || path.startsWith("/api/health")          // 健康检查（探针/负载均衡用，无登录态）
                || path.startsWith("/api/metrics")         // Prometheus 指标（监控系统抓取，只暴露聚合数字）
                || path.startsWith("/api/client-error");   // 前端错误上报（浏览器匿名上报）
    }

    /** 后台管理接口：必须有后台令牌或后台 Session */
    private boolean isAdminApi(String path) {
        return path.startsWith("/api/admin")
                // 富文本正文媒体上传：仅后台编辑器使用，同样要求后台身份
                || path.startsWith("/file/uploadEditorImage")
                || path.startsWith("/file/uploadEditorVideo")
                // 文化封面上传属于后台内容管理（原先任何登录用户都能改任意文化的封面）
                || path.startsWith("/file/uploadCultureFmFile");
    }

    /** 前台用户接口：必须有前台令牌或已登录 Session */
    private boolean isFrontApi(String path) {
        return path.startsWith("/api/user")
                || path.startsWith("/api/culture/like/")
                || path.startsWith("/api/culture/cancel/");
    }

    /** 其余受保护的 API */
    private boolean isProtectedApi(String path) {
        return path.startsWith("/api/")
                && !path.startsWith("/api/auth/")
                && !isPublic(path)
                && !isAdminApi(path)
                && !isFrontApi(path);
    }

    private boolean isActive(User user) {
        return user != null
                && (user.getDeleted() == null || user.getDeleted() != 1)
                && (user.getStatus() == null || user.getStatus() == 1);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String authorization = request.getHeader("Authorization");

        // ===== 1) 解析 Bearer 令牌：先用后台密钥、再用前台密钥（两套密钥互不通用）=====
        Scope bearerScope = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            for (Scope scope : new Scope[]{Scope.ADMIN, Scope.FRONT}) {
                Long userId;
                try {
                    userId = jwtService.getUserId(token, scope);
                } catch (Exception notThisScope) {
                    continue;
                }
                User user = userService.findById(userId);
                if (isActive(user)) {
                    bearerScope = scope;
                    applyAuthentication(request, user, scope);
                    maybeRefreshToken(request, response, token, scope, user);   // 滑动续期（响应头）
                }
                break; // 令牌能被某套密钥解析即已确定作用域，不再尝试另一套
            }
        }

        // ===== 2) 按路径鉴权（纯 Token，无 Session 兜底）=====
        // 说明：此处原先还有一段「Session 兜底」——把后台登录写入的 Spring Security
        // 会话当作登录态。改用纯 Token 后服务端不再创建会话（见 WebSecurityConfig 的
        // SessionCreationPolicy.STATELESS），因此这段逻辑已整体移除。
        if (isAdminApi(path)) {
            if (bearerScope == Scope.ADMIN) {
                chain.doFilter(request, response);
                return;
            }
            if (bearerScope == Scope.FRONT) {
                writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                        "该账号无后台管理权限，请从后台登录入口登录");
                return;
            }
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或后台凭证无效");
            return;
        }

        if (isFrontApi(path)) {
            if (bearerScope != null) {
                chain.doFilter(request, response);
                return;
            }
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或凭证无效");
            return;
        }

        if (isProtectedApi(path) && bearerScope == null) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或凭证无效");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * JWT 滑动续期：令牌合法但「剩余有效期不足总有效期的一半」时，签发新令牌并通过
     * {@code X-Refreshed-Token} 响应头下发（前端静默替换）。不改变原有响应体，续期失败也不影响请求。
     */
    private void maybeRefreshToken(HttpServletRequest request, HttpServletResponse response,
                                   String token, Scope scope, User user) {
        try {
            long ttl = jwtService.ttlMillis(scope);
            if (ttl <= 0) return;
            java.util.Date expiration = jwtService.parse(token, scope).getExpiration();
            if (expiration == null) return;
            long remaining = expiration.getTime() - System.currentTimeMillis();
            if (remaining > ttl / 2) return;
            String refreshed = jwtService.generateToken(user.getId(), user.getEmail(), scope);
            response.setHeader("X-Refreshed-Token", refreshed);
            String expose = response.getHeader("Access-Control-Expose-Headers");
            response.setHeader("Access-Control-Expose-Headers",
                    expose == null ? "X-Refreshed-Token" : expose + ", X-Refreshed-Token");
        } catch (Exception e) {
            // 续期只是体验优化，任何异常都忽略
        }
    }

    /**
     * 把令牌身份写入请求属性与 SecurityContext（<b>不写 Session</b>）。
     *
     * <p>SecurityContext 仍然要写：Spring Security 的
     * {@code anyRequest().authenticated()} 与后续可能的
     * {@code @PreAuthorize} 都依赖它。</p>
     *
     * <p>相较升级前的两处简化：</p>
     * <ol>
     *   <li>不再创建 HttpSession（原实现每个带 Token 的请求都会建一个会话，
     *       「无状态 JWT」名不副实）；</li>
     *   <li>后台令牌不再调用 {@code userDetailsService.loadUserByUsername()}。
     *       那一步会额外发出「用户 + 角色 + 权限」三条 SQL，而项目里
     *       <b>没有任何 @PreAuthorize</b> 用到这些细粒度权限 —— 真正的后台鉴权
     *       由本过滤器的作用域判断与各控制器自己的 {@code isAdmin()} 完成。
     *       去掉后每个后台请求少 3 条无关查询。</li>
     * </ol>
     */
    private void applyAuthentication(HttpServletRequest request, User user, Scope scope) {
        request.setAttribute(ATTR_LOGIN_USER_ID, user.getId());
        request.setAttribute(ATTR_TOKEN_SCOPE, scope.value());

        // 作用域 -> 角色：后台令牌只得 ROLE_ADMIN，前台令牌只得 ROLE_FRONT_USER。
        // 两者由不同密钥签发，前台令牌无法伪造出后台作用域。
        String role = scope == Scope.ADMIN ? ROLE_ADMIN_NAME : "FRONT_USER";

        // principal 直接放已加载的 User 实体（而不是把 id 拼成字符串）：
        // 业务层通过 CommonUtil.getLoginUser() 取当前登录人，它按 principal 类型解析。
        // 用实体承载可以避免为了取一个 id 再去查一次库。
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                user, null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
