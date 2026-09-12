package com.culture.api;

import com.alibaba.druid.pool.DruidDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 轻量健康检查（不引入 spring-boot-starter-actuator，离线环境无法新增依赖）
 *
 * <p>GET /api/health 免登录，返回 DB / Redis / 上传目录状态与运行时长。
 * 设计要点：<b>依赖挂掉也必须快速返回 200</b>（用 status 字段表达），否则健康检查本身
 * 会变成故障放大器（例如 DB 挂了、Druid 取连接要等 maxWait=60s，探针就会超时）。
 * 因此每项检查都放在带超时的线程里执行。</p>
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private static final long CHECK_TIMEOUT_MS = 1200;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long STARTED_AT = System.currentTimeMillis();

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final String cultureUploadPath;
    private final boolean redisConfigured;

    public HealthController(DataSource dataSource,
                            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisProvider,
                            @Value("${culture.upload.path:}") String cultureUploadPath) {
        this.dataSource = dataSource;
        this.redisTemplate = redisProvider.getIfAvailable();
        this.redisConfigured = this.redisTemplate != null;
        this.cultureUploadPath = cultureUploadPath;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean db;
        boolean redis;

        long t0 = System.currentTimeMillis();
        db = withTimeout(this::checkDb, CHECK_TIMEOUT_MS, "db");
        redis = redisConfigured && withTimeout(this::checkRedis, CHECK_TIMEOUT_MS, "redis");
        boolean disk = checkDisk();

        String status = db ? (redis || !redisConfigured ? "UP" : "DEGRADED") : "DOWN";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("db", db);
        data.put("redis", redis);
        data.put("redisConfigured", redisConfigured);
        data.put("disk", disk);
        data.put("uptimeMs", System.currentTimeMillis() - STARTED_AT);
        data.put("time", LocalDateTime.now().format(TIME_FMT));
        data.put("costMs", System.currentTimeMillis() - t0);

        body.put("code", 200);
        body.put("message", status);
        body.put("data", data);
        return body;
    }

    /** DataSource 连接可用性（Druid 取连接可能很慢，所以外层有超时保护） */
    private boolean checkDb() {
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            return conn != null && conn.isValid(1);
        } catch (Exception e) {
            log.warn("[health] 数据库不可用：{}", e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception ignore) { /* 忽略 */ }
            }
        }
    }

    private boolean checkRedis() {
        try {
            // 任意一次真实往返即可确认 Redis 可用（用 hasKey 避免 execute(...) 的重载歧义）
            redisTemplate.hasKey("health:probe");
            return true;
        } catch (Exception e) {
            log.warn("[health] Redis 不可用：{}", e.getMessage());
            return false;
        }
    }

    private boolean checkDisk() {
        try {
            if (cultureUploadPath == null || cultureUploadPath.trim().isEmpty()) return true;
            File dir = new File(cultureUploadPath);
            while (dir != null && !dir.exists()) dir = dir.getParentFile();
            return dir != null && dir.canWrite();
        } catch (Exception e) {
            return false;
        }
    }

    /** 带超时执行检查：超时/异常都返回 false，保证接口本身永远快速返回 */
    private boolean withTimeout(Callable<Boolean> task, long timeoutMs, String name) {
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "health-check-" + name);
            t.setDaemon(true);
            return t;
        });
        try {
            Future<Boolean> f = pool.submit(task);
            return Boolean.TRUE.equals(f.get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            log.warn("[health] {} 检查超时或失败：{}", name, e.getMessage());
            return false;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 供其它组件复用的运行时长（毫秒） */
    public static long uptimeMs() {
        return System.currentTimeMillis() - STARTED_AT;
    }

    /** 便于本地排查：Druid 连接池快照（非健康检查路径，只有日志用到） */
    @SuppressWarnings("unused")
    private Map<String, Object> poolSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (dataSource instanceof DruidDataSource) {
            DruidDataSource d = (DruidDataSource) dataSource;
            m.put("active", d.getActiveCount());
            m.put("pooling", d.getPoolingCount());
            m.put("waiting", d.getWaitThreadCount());
        }
        return m;
    }
}
