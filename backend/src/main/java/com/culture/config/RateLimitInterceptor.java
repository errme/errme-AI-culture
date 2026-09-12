package com.culture.config;

import com.culture.service.ConfigService;
import com.culture.util.SimpleRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接口限流拦截器（Redis 计数，滑动窗口按分钟）
 *
 * <p>为什么需要：项目里此前只有「登录失败锁定」和「评论 60 秒限流」，
 * 而**导出 CSV、全站搜索、回收站彻底删除**这类重操作没有任何限制：
 * 一个脚本就能把数据库/磁盘打满。这里按路径给出不同的每分钟上限。</p>
 *
 * <p>降级策略：Redis 不可用或计数异常时**一律放行**（限流是保护手段，不能变成新的故障点）。
 * 被限流时返回 429 + JSON，前端 axios 拦截器会把 message 显示在顶部提示里。</p>
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /**
     * 路径前缀 → 配置键（顺序匹配，先命中的生效）。
     *
     * <p><b>为什么"哪些接口需要限流"仍写在代码里、而"限流阈值"放进数据库：</b></p>
     * <ul>
     *   <li>「这个接口是重操作（全表扫描 / 不可逆 / 覆盖式写入）」属于<b>代码语义</b>，
     *       新增一个重接口时本来就要改代码，放在这里能让它和接口一起被 review；</li>
     *   <li>而「每分钟允许多少次」是纯运营参数，会随流量、攻击情况、业务节奏变化，
     *       因此放进 sys_config，由后台「系统设置 → 接口限流」维护，改完即时生效，
     *       不需要改配置重启。原先这里是把阈值硬编码在静态 Map 里的。</li>
     * </ul>
     */
    private static final Map<String, String> LIMITED_PATHS = new LinkedHashMap<>();

    static {
        LIMITED_PATHS.put("/api/admin/culture/export", "limit.export-per-minute");      // CSV 导出：全表流式扫描，最重
        LIMITED_PATHS.put("/api/admin/recycle/purge", "limit.purge-per-minute");        // 彻底删除：不可逆
        LIMITED_PATHS.put("/api/admin/culture/rollback", "limit.rollback-per-minute");  // 版本回滚
        LIMITED_PATHS.put("/api/admin/permission/role", "limit.permission-per-minute"); // 权限覆盖写
        LIMITED_PATHS.put("/api/admin/permission/button", "limit.permission-per-minute");
        LIMITED_PATHS.put("/api/search", "limit.search-per-minute");                    // 全站搜索
    }

    /** 配置项缺失/数据库不可用时的兜底阈值（与 docs/sql/13_sys_config.sql 的默认值一致） */
    private static final Map<String, Integer> FALLBACK_LIMITS = new LinkedHashMap<>();

    static {
        FALLBACK_LIMITS.put("limit.export-per-minute", 5);
        FALLBACK_LIMITS.put("limit.purge-per-minute", 20);
        FALLBACK_LIMITS.put("limit.rollback-per-minute", 20);
        FALLBACK_LIMITS.put("limit.permission-per-minute", 30);
        FALLBACK_LIMITS.put("limit.search-per-minute", 60);
    }

    private final SimpleRateLimiter limiter;
    private final ConfigService configService;

    public RateLimitInterceptor(ObjectProvider<StringRedisTemplate> redisProvider, ConfigService configService) {
        this.limiter = new SimpleRateLimiter(redisProvider.getIfAvailable());
        this.configService = configService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        Integer limit = null;
        for (Map.Entry<String, String> e : LIMITED_PATHS.entrySet()) {
            if (path.startsWith(e.getKey())) {
                // 阈值每次请求都从 ConfigService 读（内存缓存，无额外查库开销），
                // 因此后台改完限流阈值立即生效
                String configKey = e.getValue();
                Integer fallback = FALLBACK_LIMITS.get(configKey);
                limit = configService.getInt(configKey, fallback == null ? 60 : fallback);
                break;
            }
        }
        if (limit == null || limit <= 0) return true;

        String ip = clientIp(request);
        String key = "rl:" + path + ":" + ip;
        if (limiter.allow(key, limit, 60)) return true;

        // 被限流：返回 429 + 统一信封，避免前端把它当 500
        log.warn("[rate-limit] 触发限流 path={} ip={} limit={}/min", path, ip, limit);
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"code\":429,\"message\":\"操作过于频繁，请稍后再试\",\"data\":null}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        return false;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.trim().isEmpty()) return real.trim();
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
