package com.culture.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 服务：签发与解析令牌。
 *
 * <p>前台（用户端）与后台（管理端）使用<b>两套独立密钥</b>，令牌作用域写入 {@code scope} 声明，
 * 解析时必须「密钥 + 作用域」同时匹配，因此：
 * <ul>
 *   <li>前台令牌拿去访问 /api/admin/** 一定失败（密钥不同，签名校验不过）；</li>
 *   <li>后台令牌也无法冒充前台用户令牌；</li>
 *   <li>两套令牌各自有独立有效期（前台 24h、后台 8h，均可配置）。</li>
 * </ul>
 * 密钥通过环境变量 AUTH_JWT_FRONT_SECRET / AUTH_JWT_ADMIN_SECRET 注入，本地有默认值。</p>
 */
@Service
public class JwtService {

    /** 令牌作用域：前台（用户端）/ 后台（管理端） */
    public enum Scope {
        FRONT("front"),
        ADMIN("admin");

        private final String value;

        Scope(String value) { this.value = value; }

        public String value() { return value; }

        public static Scope of(String value) {
            return ADMIN.value.equals(value) ? ADMIN : FRONT;
        }
    }

    /** 作用域声明名 */
    public static final String CLAIM_SCOPE = "scope";
    /** 账号类型声明名：front=前台用户，admin=后台管理员 */
    public static final String CLAIM_TYPE = "userType";

    private final SecretKey frontKey;
    private final SecretKey adminKey;

    /**
     * 登录有效期的兜底值（小时，来自 application.yml）。
     * 实际取值优先读数据库（后台「系统设置 → 登录有效期」），这里只作回退。
     *
     * <p>注意：有效期原先在构造时就被换算成毫秒常量，导致改了配置必须重启才生效；
     * 现在改成<b>每次签发/解析时读取</b>，后台改完即时生效。</p>
     */
    private final long frontExpireHoursFallback;
    private final long adminExpireHoursFallback;

    /** 系统配置来源（登录有效期等运行期可变项） */
    private final com.culture.service.ConfigService configService;

    public JwtService(@Value("${app.jwt.front-secret}") String frontSecret,
                      @Value("${app.jwt.admin-secret}") String adminSecret,
                      com.culture.service.ConfigService configService,
                      @Value("${app.jwt.front-expire-hours:24}") long frontExpireHours,
                      @Value("${app.jwt.admin-expire-hours:8}") long adminExpireHours) {
        this.frontKey = Keys.hmacShaKeyFor(frontSecret.getBytes(StandardCharsets.UTF_8));
        this.adminKey = Keys.hmacShaKeyFor(adminSecret.getBytes(StandardCharsets.UTF_8));
        this.configService = configService;
        this.frontExpireHoursFallback = frontExpireHours;
        this.adminExpireHoursFallback = adminExpireHours;
    }

    /** 指定作用域的令牌有效期（毫秒）：后台可改，未配置时回退 application.yml */
    private long expireMillis(Scope scope) {
        boolean admin = scope == Scope.ADMIN;
        int fallbackHours = (int) (admin ? adminExpireHoursFallback : frontExpireHoursFallback);
        int hours = configService.getInt(
                admin ? "jwt.admin-expire-hours" : "jwt.front-expire-hours", fallbackHours);
        // 兜底：配置被改坏（0 或负数）时至少给 1 小时，避免签发出「即刻过期」的令牌
        if (hours <= 0) hours = Math.max(1, fallbackHours);
        return hours * 3600L * 1000L;
    }

    /** 签发令牌：subject = 用户ID，附带 email / scope / userType 声明 */
    public String generateToken(Long userId, String email, Scope scope) {
        Date now = new Date();
        long expire = expireMillis(scope);
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("email", email)
                .claim(CLAIM_SCOPE, scope.value())
                .claim(CLAIM_TYPE, scope.value())
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expire))
                .signWith(keyOf(scope), SignatureAlgorithm.HS256)
                .compact();
    }

    /** 兼容旧调用：默认签发前台令牌 */
    public String generateToken(Long userId, String email) {
        return generateToken(userId, email, Scope.FRONT);
    }

    /**
     * 按作用域解析令牌：使用该作用域的密钥验签，并强制校验 scope 声明。
     * 密钥不匹配、签名错误、过期、scope 不符都会抛出异常。
     */
    public Claims parse(String token, Scope scope) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(keyOf(scope))
                .build()
                .parseClaimsJws(token)
                .getBody();
        if (!scope.value().equals(claims.get(CLAIM_SCOPE, String.class))) {
            throw new JwtException("令牌作用域不匹配");
        }
        return claims;
    }

    /** 从令牌取用户ID（按作用域校验） */
    public Long getUserId(String token, Scope scope) {
        return Long.valueOf(parse(token, scope).getSubject());
    }

    /** 该作用域令牌的总有效期（毫秒），用于「滑动续期」判断剩余比例 */
    public long ttlMillis(Scope scope) {
        return expireMillis(scope);
    }

    /** 作用域对应的签名密钥：前后台令牌互不通用 */
    private SecretKey keyOf(Scope scope) {
        return scope == Scope.ADMIN ? adminKey : frontKey;
    }
}
