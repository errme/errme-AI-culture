package com.culture.auth.controller;

import com.culture.auth.dto.ApiResponse;
import com.culture.auth.dto.LoginRequest;
import com.culture.auth.dto.RegisterRequest;
import com.culture.auth.dto.ResetRequest;
import com.culture.auth.dto.SendCodeRequest;
import com.culture.auth.service.AuthService;
import com.culture.auth.service.BusinessException;
import com.culture.auth.service.JwtService;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口（前后端分离 REST API，前台 / 后台登录完全分离）：
 *
 * <pre>
 * 前台（用户端）                                    后台（管理端）
 * POST /api/auth/send-code                          同左（注册/找回密码复用）
 * POST /api/auth/register                           同左
 * POST /api/auth/login          -> 前台专用 Token     POST /api/auth/admin/login   -> 后台专用 Token
 * POST /api/auth/logout                              POST /api/auth/admin/logout
 * GET  /api/auth/me                                  GET  /api/auth/admin/me
 * </pre>
 *
 * 两套 Token 使用不同密钥 + 不同 scope 声明，互不通用：
 * 前台 Token 访问 /api/admin/** 返回 403，后台 Token 也不能当作前台用户身份使用。
 * 统一响应：{ success, message, data }
 */
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

    public AuthController(AuthService authService, JwtService jwtService,
                          org.springframework.security.core.userdetails.UserDetailsService userDetailsService) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    // ==================== 注册 / 验证码 / 找回密码（前后台共用） ====================

    /** 发送验证码（60 秒限频、5 分钟有效、一次性使用） */
    @PostMapping("/send-code")
    public ApiResponse<Map<String, Object>> sendCode(@Valid @RequestBody SendCodeRequest req) {
        return ApiResponse.ok("验证码已发送", authService.sendCode(req.getEmail(), req.getScene()));
    }

    /** 注册（前台用户注册） */
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.ok("注册成功", authService.register(req.getEmail(), req.getCode(), req.getPassword()));
    }

    /** 重置密码 */
    @PostMapping("/reset")
    public ApiResponse<Void> reset(@Valid @RequestBody ResetRequest req) {
        authService.reset(req.getEmail(), req.getCode(), req.getNewPassword());
        return ApiResponse.ok("密码已重置", null);
    }

    // ==================== 前台登录（前台用户） ====================

    /**
     * 前台登录：签发<b>前台专用令牌</b>，只做前台 Session 桥接（个人中心/收藏），
     * <b>不会</b>建立后台管理认证，因此前台登录无法进入后台管理页面。
     */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        Map<String, Object> data = authService.login(req.getEmail(), req.getPassword(), JwtService.Scope.FRONT);
        HttpSession session = request.getSession(true);
        session.setAttribute("loginUserName", data.get("username"));
        session.setAttribute("loginUserId", data.get("id"));
        session.setAttribute("tokenScope", JwtService.Scope.FRONT.value());
        // 前台登录不写 Spring Security 会话：后台权限只认后台登录
        return ApiResponse.ok("登录成功", data);
    }

    /** 前台当前用户信息（前台令牌） */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireUserId(authorization, JwtService.Scope.FRONT);
        Map<String, Object> data = authService.me(userId);
        data.put("scope", JwtService.Scope.FRONT.value());
        data.put("admin", authService.isAdmin(userId));
        return ApiResponse.ok(data);
    }

    /** 前台退出登录：清理前台 Session 桥接信息 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute("loginUserName");
            session.removeAttribute("loginUserId");
            session.removeAttribute("tokenScope");
        }
        return ApiResponse.ok("已退出登录", null);
    }

    // ==================== 后台登录（管理端，独立入口 /static/auth/admin-login.html） ====================

    /**
     * 后台登录：必须是「管理员」账号，签发<b>后台专用令牌</b>（另一套密钥 + scope=admin），
     * 并建立 Spring Security 会话，使原有 Thymeleaf 后台页面（/admin/**）可直接使用。
     */
    @PostMapping("/admin/login")
    public ApiResponse<Map<String, Object>> adminLogin(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        Map<String, Object> data = authService.login(req.getEmail(), req.getPassword(), JwtService.Scope.ADMIN);
        // 后台页面是 Session 认证：写入完整 Spring Security 上下文（含角色/权限）
        establishAdminSession(request, String.valueOf(data.get("username")));
        HttpSession session = request.getSession(true);
        session.setAttribute("loginUserName", data.get("username"));
        session.setAttribute("loginUserId", data.get("id"));
        session.setAttribute("tokenScope", JwtService.Scope.ADMIN.value());
        session.setAttribute("adminLoginTime", System.currentTimeMillis());
        return ApiResponse.ok("后台登录成功", data);
    }

    /** 后台当前登录信息（后台令牌） */
    @GetMapping("/admin/me")
    public ApiResponse<Map<String, Object>> adminMe(@RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireUserId(authorization, JwtService.Scope.ADMIN);
        Map<String, Object> data = new LinkedHashMap<>(authService.me(userId));
        data.put("scope", JwtService.Scope.ADMIN.value());
        data.put("admin", authService.isAdmin(userId));
        return ApiResponse.ok(data);
    }

    /** 后台退出登录：注销 Session（含 Spring Security 上下文） */
    @PostMapping("/admin/logout")
    public ApiResponse<Void> adminLogout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ApiResponse.ok("已退出后台登录", null);
    }

    // ==================== 内部工具 ====================

    /**
     * 通过 UserDetailsService 加载用户（含角色/权限）并写入 SecurityContext + Session，
     * 使后台登录后可直接访问原有 Thymeleaf 后台管理页面。
     */
    private void establishAdminSession(HttpServletRequest request, String username) {
        org.springframework.security.core.userdetails.UserDetails ud = userDetailsService.loadUserByUsername(username);
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        ud, null, ud.getAuthorities());
        org.springframework.security.core.context.SecurityContext ctx =
                org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
        request.getSession(true).setAttribute(
                org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);
    }

    /** 按指定作用域解析令牌，取用户ID；令牌缺失/作用域不符统一抛业务异常 */
    private Long requireUserId(String authorization, JwtService.Scope scope) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException("未登录或凭证无效");
        }
        Claims claims;
        try {
            claims = jwtService.parse(authorization.substring(7).trim(), scope);
        } catch (Exception e) {
            throw new BusinessException(scope == JwtService.Scope.ADMIN
                    ? "后台登录状态无效或已过期，请重新登录"
                    : "登录状态无效或已过期，请重新登录");
        }
        return Long.valueOf(claims.getSubject());
    }

    // ==================== 统一异常处理 ====================

    /** 统一参数校验错误处理（400） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("参数错误");
        return ApiResponse.error(message);
    }

    /** 统一业务异常处理（400） */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBusiness(BusinessException e) {
        return ApiResponse.error(e.getMessage());
    }

    /** 统一兜底异常处理（500，不向前端泄露堆栈） */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleOther(Exception e) {
        e.printStackTrace();
        return ApiResponse.error("服务器内部错误");
    }
}
