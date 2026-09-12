package com.culture.config;

import com.culture.util.SimpleRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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

    /** 路径前缀 → 每分钟上限（顺序匹配，先命中的生效） */
    private static final Map<String, Integer> LIMITS = new LinkedHashMap<>();

    static {
        LIMITS.put("/api/admin/culture/export", 5);      // CSV 导出：全表流式扫描，最重
        LIMITS.put("/api/admin/recycle/purge", 20);      // 彻底删除：不可逆
        LIMITS.put("/api/admin/culture/rollback", 20);   // 版本回滚
        LIMITS.put("/api/admin/permission/role", 30);    // 权限覆盖写
        LIMITS.put("/api/admin/permission/button", 30);
        LIMITS.put("/api/search", 60);                   // 全站搜索
    }

    private final SimpleRateLimiter limiter;

    public RateLimitInterceptor(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.limiter = new SimpleRateLimiter(redisProvider.getIfAvailable());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        Integer limit = null;
        for (Map.Entry<String, Integer> e : LIMITS.entrySet()) {
            if (path.startsWith(e.getKey())) { limit = e.getValue(); break; }
        }
        if (limit == null) return true;

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
