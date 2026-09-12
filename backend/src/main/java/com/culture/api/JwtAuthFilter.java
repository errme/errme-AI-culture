package com.culture.api;

import com.culture.auth.service.JwtService;
import com.culture.auth.service.JwtService.Scope;
import com.culture.config.UserSecurity;
import com.culture.entity.User;
import com.culture.service.UserService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
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
    private final UserDetailsService userDetailsService;

    public JwtAuthFilter(JwtService jwtService, UserService userService,
                         UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.userDetailsService = userDetailsService;
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

    /** 是否为后台 Session 管理员 */
    private boolean isAdminSession(Authentication auth) {
        return auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof UserSecurity
                && auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_" + ROLE_ADMIN_NAME));
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

        // ===== 2) Session 兜底（后台表单/Session 登录、前台登录桥接）=====
        Authentication sessionAuth = SecurityContextHolder.getContext().getAuthentication();
        boolean adminSession = isAdminSession(sessionAuth);
        if (bearerScope == null && adminSession) {
            UserSecurity us = (UserSecurity) sessionAuth.getPrincipal();
            request.setAttribute(ATTR_LOGIN_USER_ID, us.getLoginUser().getId());
            request.setAttribute(ATTR_TOKEN_SCOPE, "admin");
        }
        HttpSession session = request.getSession(false);
        boolean sessionLogin = adminSession || (session != null && session.getAttribute("loginUserId") != null);

        // ===== 3) 按路径鉴权 =====
        if (isAdminApi(path)) {
            if (bearerScope == Scope.ADMIN || adminSession) {
                chain.doFilter(request, response);
                return;
            }
            if (bearerScope == Scope.FRONT || sessionLogin) {
                writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                        "该账号无后台管理权限，请从后台登录入口登录");
                return;
            }
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或后台凭证无效");
            return;
        }

        if (isFrontApi(path)) {
            if (bearerScope != null || sessionLogin) {
                chain.doFilter(request, response);
                return;
            }
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或凭证无效");
            return;
        }

        if (isProtectedApi(path) && bearerScope == null && !sessionLogin) {
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

    /** 把令牌身份写入请求属性 / Session / SecurityContext */
    private void applyAuthentication(HttpServletRequest request, User user, Scope scope) {
        request.setAttribute(ATTR_LOGIN_USER_ID, user.getId());
        request.setAttribute(ATTR_TOKEN_SCOPE, scope.value());

        HttpSession session = request.getSession(true);
        // Session 桥接：让原始 Thymeleaf 前台页面（个人中心/收藏/发布）能读到登录人
        session.setAttribute("loginUserName", user.getUsername());
        session.setAttribute("loginUserId", user.getId());

        if (scope == Scope.ADMIN) {
            // 后台令牌：加载角色/权限，写入完整认证并持久化到 Session（后台 Thymeleaf 页面依赖 Session）
            UserDetails ud = userDetailsService.loadUserByUsername(user.getUsername());
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    SecurityContextHolder.getContext());
            session.setAttribute("tokenScope", Scope.ADMIN.value());
        } else {
            // 前台令牌：仅最小权限，绝不携带管理员角色（后台权限只认后台令牌/后台 Session）
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    "front:" + user.getId(), null,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_FRONT_USER")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            session.setAttribute("tokenScope", Scope.FRONT.value());
        }
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
