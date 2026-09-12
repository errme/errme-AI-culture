package com.culture.auth.service;

import com.culture.entity.User;
import com.culture.mapper.UserMapper;
import com.culture.service.RoleService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务（新库 sys_user + Redis 验证码）。
 * - 验证码存储于 Redis：key vc:{email}（5 分钟过期、一次性使用）；
 * - 重发限制：key vc:resend:{email}（60 秒过期）；
 * - 密码统一 BCrypt 加密；登录成功更新 last_login_at；
 * - 邮箱统一转小写存储，避免大小写重复注册；
 * - <b>前台登录与后台登录分离</b>：同一套账号体系，但 API、令牌作用域、令牌密钥均不同，
 *   后台登录额外校验「管理员」角色。
 */
@Service
public class AuthService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthService.class);

    private final UserMapper userMapper;
    private final JwtService jwtService;
    private final MailService mailService;
    private final RoleService roleService;
    private final StringRedisTemplate redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    private final int codeExpireMinutes;
    private final int resendSeconds;
    private final boolean returnCodeInResponse;

    public AuthService(UserMapper userMapper,
                       JwtService jwtService,
                       MailService mailService,
                       RoleService roleService,
                       StringRedisTemplate redisTemplate,
                       @Value("${app.code.expire-minutes}") int codeExpireMinutes,
                       @Value("${app.code.resend-seconds}") int resendSeconds,
                       @Value("${app.code.return-in-response:false}") boolean returnCodeInResponse) {
        this.userMapper = userMapper;
        this.jwtService = jwtService;
        this.mailService = mailService;
        this.roleService = roleService;
        this.redisTemplate = redisTemplate;
        this.codeExpireMinutes = codeExpireMinutes;
        this.resendSeconds = resendSeconds;
        this.returnCodeInResponse = returnCodeInResponse;
    }

    private String codeKey(String email) { return "vc:" + email; }
    private String resendKey(String email) { return "vc:resend:" + email; }

    /**
     * 发送验证码：校验场景 -> 60 秒限频 -> 存入 Redis -> 异步发邮件。
     * 注意：接口不返回验证码本身，避免被前端截获（生产安全）。
     */
    public Map<String, Object> sendCode(String email, String scene) {
        String normalized = normalize(email);

        // 按场景校验账号状态：注册需未注册邮箱，忘记密码需已注册邮箱
        User exists = userMapper.findByEmail(normalized);
        if ("forgot".equalsIgnoreCase(scene)) {
            if (exists == null) {
                throw new BusinessException("该邮箱尚未注册，请先注册");
            }
        } else {
            if (exists != null) {
                throw new BusinessException("该邮箱已注册，请直接登录");
            }
        }

        // 邮件发送上限校验（超限直接提示，避免"已发送却收不到"）
        mailService.assertCanSend();

        // 60 秒重发限制（Redis）
        if (Boolean.TRUE.equals(redisTemplate.hasKey(resendKey(normalized)))) {
            Long ttl = redisTemplate.getExpire(resendKey(normalized), TimeUnit.SECONDS);
            throw new BusinessException("请 " + Math.max(1, ttl == null ? resendSeconds : ttl) + " 秒后再试");
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        redisTemplate.opsForValue().set(codeKey(normalized), code, codeExpireMinutes, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(resendKey(normalized), "1", resendSeconds, TimeUnit.SECONDS);

        // 异步发送验证码邮件，接口立即返回；失败仅记日志（验证码仍在 Redis，便于本地联调）
        CompletableFuture.runAsync(() -> {
            try {
                mailService.sendCode(normalized, code, codeExpireMinutes);
            } catch (Exception ex) {
                System.out.println("[落款·邮件发送失败] " + normalized + " : " + ex.getMessage());
            }
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("email", normalized);
        data.put("expireMinutes", codeExpireMinutes);
        data.put("resendSeconds", resendSeconds);
        // 本地联调（app.code.return-in-response=true）：验证码直接返回，前端自动填入；
        // 生产环境请关闭，验证码只通过邮件送达。
        if (returnCodeInResponse) {
            data.put("code", code);
        }
        return data;
    }

    /**
     * 注册：校验验证码（Redis 一次性）-> 创建用户（默认启用，来源=email）。
     * username 取邮箱@前缀，若冲突则追加 4 位随机数。
     */
    @Transactional
    public Map<String, Object> register(String email, String code, String password) {
        String normalized = normalize(email);
        if (userMapper.findByEmail(normalized) != null) {
            throw new BusinessException("该邮箱已注册，请直接登录");
        }
        verifyCode(normalized, code);

        String prefix = normalized.split("@")[0];
        String username = prefix;
        // 用户名唯一性：冲突时追加随机数字
        for (int i = 0; i < 5 && userMapper.findUserByUserName(username) != null; i++) {
            username = prefix + (1000 + random.nextInt(9000));
        }
        if (userMapper.findUserByUserName(username) != null) {
            throw new BusinessException("用户名生成冲突，请重试");
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(normalized);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(prefix);
        user.setStatus(1);
        user.setSource("email");
        user.setCreateTime(new Date());
        userMapper.addUser(user);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("email", user.getEmail());
        data.put("username", user.getUsername());
        return data;
    }

    /**
     * 登录（统一实现，按作用域签发不同令牌）：
     * <ul>
     *   <li>{@link JwtService.Scope#FRONT}：前台用户登录，签发前台专用令牌；</li>
     *   <li>{@link JwtService.Scope#ADMIN}：后台管理登录，<b>额外要求账号具备「管理员」角色</b>，
     *       并签发后台专用令牌（另一套密钥，前台令牌无法替代）。</li>
     * </ul>
     */
    /** 登录失败锁定策略：15 分钟内失败 5 次 → 锁定 10 分钟 */
    private static final int LOGIN_FAIL_LIMIT = 5;
    private static final long LOGIN_FAIL_WINDOW_MINUTES = 15;
    private static final long LOGIN_LOCK_MINUTES = 10;

    public Map<String, Object> login(String emailOrName, String password, JwtService.Scope scope) {
        String input = emailOrName == null ? "" : emailOrName.trim();
        if (input.isEmpty()) {
            throw new BusinessException("请输入账号");
        }
        // 防暴力破解：先看是否处于锁定期（Redis 不可用时降级为不锁定，不能因为缓存挂了就无法登录）
        if (isLoginLocked(input)) {
            throw new BusinessException("失败次数过多，请 " + LOGIN_LOCK_MINUTES + " 分钟后再试");
        }
        // 含 @ 按邮箱查，否则按用户名查（兼容两种登录方式）
        User user = input.contains("@")
                ? userMapper.findByEmail(input.toLowerCase())
                : userMapper.findUserByUserName(input);
        if (user == null) {
            recordLoginFailure(input);
            throw new BusinessException("该账号尚未注册，请先注册");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException("该账号已被禁用，请联系管理员");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            recordLoginFailure(input);
            throw new BusinessException("密码不正确");
        }
        clearLoginFailure(input);

        boolean admin = isAdmin(user.getId());
        // 后台登录：必须是管理员账号，普通用户即使密码正确也不允许登录后台
        if (scope == JwtService.Scope.ADMIN && !admin) {
            throw new BusinessException("该账号不是管理员，无法登录后台");
        }

        // 更新最后登录时间（失败不影响主流程）
        userMapper.updateLastLoginAt(user.getId(), new Date());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), scope);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("token", token);
        data.put("scope", scope.value());
        data.put("admin", admin);
        data.put("email", user.getEmail());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        return data;
    }

    /** 兼容旧调用：默认前台登录 */
    public Map<String, Object> login(String emailOrName, String password) {
        return login(emailOrName, password, JwtService.Scope.FRONT);
    }

    /* ==================== 登录失败锁定（Redis，失败降级） ==================== */

    private String failKey(String account) {
        return "login:fail:" + account.toLowerCase();
    }

    private String lockKey(String account) {
        return "login:lock:" + account.toLowerCase();
    }

    /** 是否处于锁定期；Redis 不可用/异常时返回 false（降级：不锁定） */
    private boolean isLoginLocked(String account) {
        if (redisTemplate == null) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(lockKey(account)));
        } catch (Exception e) {
            return false;
        }
    }

    /** 记录一次失败；达到阈值则写入锁定标记 */
    private void recordLoginFailure(String account) {
        if (redisTemplate == null) return;
        try {
            String key = failKey(account);
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, LOGIN_FAIL_WINDOW_MINUTES, TimeUnit.MINUTES);
            }
            if (count != null && count >= LOGIN_FAIL_LIMIT) {
                redisTemplate.opsForValue().set(lockKey(account), String.valueOf(System.currentTimeMillis()),
                        LOGIN_LOCK_MINUTES, TimeUnit.MINUTES);
                redisTemplate.delete(key);
                log.warn("[auth] 账号 {} 连续登录失败 {} 次，已锁定 {} 分钟", account, count, LOGIN_LOCK_MINUTES);
            }
        } catch (Exception e) {
            log.warn("[auth] 记录登录失败次数失败（忽略）：{}", e.getMessage());
        }
    }

    /** 登录成功后清空失败计数与锁定标记 */
    private void clearLoginFailure(String account) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.delete(failKey(account));
            redisTemplate.delete(lockKey(account));
        } catch (Exception e) {
            log.warn("[auth] 清理登录失败计数失败（忽略）：{}", e.getMessage());
        }
    }

    /** 是否具备「管理员」角色 */
    public boolean isAdmin(Long userId) {
        if (userId == null) return false;
        for (com.culture.entity.Role role : roleService.listRoleByUserId(userId)) {
            if ("管理员".equals(role.getName())) return true;
        }
        return false;
    }

    /**
     * 重置密码：校验验证码（Redis 一次性）-> BCrypt 更新密码。
     */
    @Transactional
    public void reset(String email, String code, String newPassword) {
        String normalized = normalize(email);
        User user = userMapper.findByEmail(normalized);
        if (user == null) {
            throw new BusinessException("该邮箱尚未注册，请先注册");
        }
        verifyCode(normalized, code);
        userMapper.updatePassword(user.getId(), passwordEncoder.encode(newPassword));
    }

    /**
     * 当前登录用户信息（token -> userId -> 用户资料）。
     */
    public Map<String, Object> me(Long userId) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("email", user.getEmail());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("avatar", user.getHeadImg());
        data.put("status", user.getStatus());
        data.put("createdAt", user.getCreateTime());
        return data;
    }

    /** 邮箱规范化：去空格 + 转小写 */
    private String normalize(String email) {
        return email.trim().toLowerCase();
    }

    /** 校验验证码：Redis 中匹配则删除（一次性使用） */
    private void verifyCode(String email, String code) {
        String stored = redisTemplate.opsForValue().get(codeKey(email));
        if (stored == null) {
            throw new BusinessException("验证码不存在或已过期，请重新获取");
        }
        if (!stored.equals(code.trim())) {
            throw new BusinessException("验证码不正确");
        }
        redisTemplate.delete(codeKey(email));
    }
}
