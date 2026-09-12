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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
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
 *
 * <p><b>无状态化（Spring Boot 3 升级时一并完成）：</b>
 * 登录/登出接口不再读写服务端 Session。此前的实现会把登录态桥接进 HttpSession
 * （后台登录还会写入完整的 Spring Security 上下文），导致：</p>
 * <ul>
 *   <li>「无状态 JWT」名不副实——每个带 Token 的请求都会创建一个服务端 Session，
 *       并发高时堆内存被 Session 吃掉，多实例部署还要处理会话一致性；</li>
 *   <li>同时存在 Cookie 会话却把 CSRF 关掉，属于自相矛盾的安全姿态。</li>
 * </ul>
 * <p>现在登录只返回 Token，登出由前端清除本地 Token 即可（服务端无状态可清）。
 * 与之配套：{@code JwtAuthFilter} 不再写 Session，{@code WebSecurityConfig}
 * 使用 {@code SessionCreationPolicy.STATELESS} 且同样不再需要禁用 CSRF 之外的特殊处理。</p>
 */
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
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
     * 前台登录：签发<b>前台专用令牌</b>（scope=front）。
     * <b>不会</b>建立后台管理认证，因此前台登录无法进入后台管理。
     * 无状态：不写任何服务端 Session。
     */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        Map<String, Object> data = authService.login(req.getEmail(), req.getPassword(), JwtService.Scope.FRONT);
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

    /**
     * 前台退出登录。
     * 纯 Token 方案下服务端无状态可清 —— 前端清除本地 Token 即完成登出；
     * 保留该接口是为了兼容既有前端调用，避免出现 404。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        return ApiResponse.ok("已退出登录", null);
    }

    // ==================== 后台登录（管理端，独立入口 /static/auth/admin-login.html） ====================

    /**
     * 后台登录：必须是「管理员」账号，签发<b>后台专用令牌</b>（另一套密钥 + scope=admin）。
     * 无状态：不再建立 Spring Security 会话（原先是为了兼容已删除的 Thymeleaf 后台页面）。
     */
    @PostMapping("/admin/login")
    public ApiResponse<Map<String, Object>> adminLogin(@Valid @RequestBody LoginRequest req) {
        Map<String, Object> data = authService.login(req.getEmail(), req.getPassword(), JwtService.Scope.ADMIN);
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

    /**
     * 后台退出登录。与前台同理：纯 Token 方案下服务端无状态可清，前端清除本地
     * admin Token 即完成登出；保留接口以兼容既有前端调用。
     */
    @PostMapping("/admin/logout")
    public ApiResponse<Void> adminLogout() {
        return ApiResponse.ok("已退出后台登录", null);
    }

    // ==================== 内部工具 ====================

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
        // 用日志框架记录（原 e.printStackTrace() 绕过 logback，生产环境等于把异常丢掉）
        log.error("[auth] 未预期的异常", e);
        return ApiResponse.error("服务器内部错误");
    }
}
