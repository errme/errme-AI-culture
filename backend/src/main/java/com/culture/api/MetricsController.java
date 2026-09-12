package com.culture.api;

import com.alibaba.druid.pool.DruidDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简指标端点（Prometheus 文本格式），不引入 micrometer/actuator 依赖（离线环境装不了）。
 *
 * <p>用途：把「服务活着吗」升级为「服务现在什么状态」——连接池是否打满、堆内存是否偏高、
 * 回收站里堆了多少待处理内容、前端上报了多少错误。可直接被抓取：
 * <pre>
 *   scrape_configs:
 *     - job_name: culture
 *       metrics_path: /api/metrics
 *       static_configs: [{ targets: ['culture-host:8081'] }]
 * </pre>
 *
 * <p>采集口径：连接池/JVM/运行时是纯内存读取，开销可忽略；业务计数（文化/待审评论/回收站）
 * 是带索引的 COUNT 查询，且**任何一项失败都不影响整份输出**（失败即省略该项）。
 * 端点免登录（与 /api/health 一致，方便探针/监控系统采集），不返回任何业务明细。</p>
 */
@RestController
@RequestMapping("/api")
public class MetricsController {

    private static final Logger log = LoggerFactory.getLogger(MetricsController.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long STARTED_AT = System.currentTimeMillis();

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final com.culture.mapper.RecycleMapper recycleMapper;
    /** 慢 SQL 统计（MyBatis 插件）；用 ObjectProvider 是为了在插件被条件关闭时也能正常出指标 */
    private final ObjectProvider<com.culture.config.SlowSqlInterceptor> slowSqlProvider;

    public MetricsController(DataSource dataSource,
                             ObjectProvider<StringRedisTemplate> redisProvider,
                             com.culture.mapper.RecycleMapper recycleMapper,
                             ObjectProvider<com.culture.config.SlowSqlInterceptor> slowSqlProvider) {
        this.dataSource = dataSource;
        this.redisTemplate = redisProvider.getIfAvailable();
        this.recycleMapper = recycleMapper;
        this.slowSqlProvider = slowSqlProvider;
    }

    /**
     * 注意：不能用 {@code produces = "text/plain"} —— 项目的 CorsConfig 固定了 JSON 内容协商
     * （ignoreAcceptHeader=true），声明 produces 会直接 406。这里改为显式设置响应头（与 SeoController 同一套路）。
     */
    @GetMapping("/metrics")
    public void metrics(javax.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.setContentType("text/plain;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.getWriter().write(build());
    }

    private String build() {
        StringBuilder sb = new StringBuilder(1024);
        line(sb, "# HELP culture_up 进程是否存活（固定为 1，配合抓取失败判断）");
        line(sb, "# TYPE culture_up gauge");
        line(sb, "culture_up 1");
        line(sb, "# HELP culture_uptime_seconds 运行时长（秒）");
        line(sb, "# TYPE culture_uptime_seconds gauge");
        line(sb, "culture_uptime_seconds " + ((System.currentTimeMillis() - STARTED_AT) / 1000));

        // ---- 连接池 ----
        if (dataSource instanceof DruidDataSource) {
            DruidDataSource d = (DruidDataSource) dataSource;
            line(sb, "# HELP culture_db_pool_active 活跃连接数");
            line(sb, "# TYPE culture_db_pool_active gauge");
            line(sb, "culture_db_pool_active " + d.getActiveCount());
            line(sb, "# HELP culture_db_pool_idle 空闲连接数");
            line(sb, "# TYPE culture_db_pool_idle gauge");
            line(sb, "culture_db_pool_idle " + d.getPoolingCount());
            line(sb, "# HELP culture_db_pool_waiting 等待连接的线程数");
            line(sb, "# TYPE culture_db_pool_waiting gauge");
            line(sb, "culture_db_pool_waiting " + d.getWaitThreadCount());
        }

        // ---- JVM ----
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        line(sb, "# HELP culture_jvm_heap_used_bytes 堆已用字节");
        line(sb, "# TYPE culture_jvm_heap_used_bytes gauge");
        line(sb, "culture_jvm_heap_used_bytes " + heap.getUsed());
        line(sb, "# HELP culture_jvm_heap_max_bytes 堆上限字节");
        line(sb, "# TYPE culture_jvm_heap_max_bytes gauge");
        line(sb, "culture_jvm_heap_max_bytes " + heap.getMax());
        line(sb, "# HELP culture_jvm_threads 线程数");
        line(sb, "# TYPE culture_jvm_threads gauge");
        line(sb, "culture_jvm_threads " + ManagementFactory.getThreadMXBean().getThreadCount());

        // ---- 业务计数（失败即省略，不影响整份输出） ----
        Map<String, Long> counts = new LinkedHashMap<>();
        try {
            Map<String, Object> recycle = safeRecycleCounts();
            for (Map.Entry<String, Object> e : recycle.entrySet()) {
                counts.put("recycle_" + e.getKey(), toLong(e.getValue()));
            }
        } catch (Exception e) {
            log.warn("[metrics] 回收站计数失败：{}", e.getMessage());
        }
        line(sb, "# HELP culture_recycle_total 回收站待处理数量（按类型）");
        line(sb, "# TYPE culture_recycle_total gauge");
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            line(sb, "culture_" + e.getKey() + " " + e.getValue());
        }

        // ---- Redis 侧计数 ----
        if (redisTemplate != null) {
            try {
                String errors = redisTemplate.opsForValue().get("client:error:total");
                line(sb, "# HELP culture_client_errors_total 前端上报错误累计数");
                line(sb, "# TYPE culture_client_errors_total counter");
                line(sb, "culture_client_errors_total " + (errors == null ? "0" : errors));
            } catch (Exception e) {
                log.warn("[metrics] Redis 计数读取失败：{}", e.getMessage());
            }
        }

        // ---- 慢 SQL（MyBatis 插件统计，见 SlowSqlInterceptor）----
        com.culture.config.SlowSqlInterceptor slow = slowSqlProvider.getIfAvailable();
        if (slow != null) {
            line(sb, "# HELP culture_slow_sql_enabled 是否开启慢 SQL 上报（0/1）");
            line(sb, "# TYPE culture_slow_sql_enabled gauge");
            line(sb, "culture_slow_sql_enabled " + (slow.isEnabled() ? 1 : 0));
            line(sb, "# HELP culture_slow_sql_threshold_ms 慢 SQL 判定阈值（毫秒）");
            line(sb, "# TYPE culture_slow_sql_threshold_ms gauge");
            line(sb, "culture_slow_sql_threshold_ms " + slow.getThresholdMs());
            line(sb, "# HELP culture_slow_sql_total 超过阈值的语句累计次数（进程内，不跨实例聚合）");
            line(sb, "# TYPE culture_slow_sql_total counter");
            line(sb, "culture_slow_sql_total " + slow.getSlowCount());
            line(sb, "# HELP culture_slow_sql_max_ms 最慢一次语句耗时（毫秒）");
            line(sb, "# TYPE culture_slow_sql_max_ms gauge");
            line(sb, "culture_slow_sql_max_ms " + slow.getMaxCostMs());
            line(sb, "# HELP culture_slow_sql_last_ms 最近一次慢语句耗时（毫秒）");
            line(sb, "# TYPE culture_slow_sql_last_ms gauge");
            line(sb, "culture_slow_sql_last_ms " + slow.getLastCostMs());
            // 最近一次慢语句只作为注释输出（不进 label，避免 Prometheus 标签基数据膨胀）
            line(sb, "# slow_sql_last_statement " + slow.getLastStatement());
        }

        line(sb, "# HELP culture_metrics_scraped_at 采集时间戳（秒）");
        line(sb, "# TYPE culture_metrics_scraped_at gauge");
        line(sb, "culture_metrics_scraped_at " + (System.currentTimeMillis() / 1000));
        line(sb, "# scraped_at " + LocalDateTime.now().format(TIME_FMT));
        return sb.toString();
    }

    private Map<String, Object> safeRecycleCounts() {
        // 复用回收站的聚合计数（单条 SQL 的 7 个标量子查询，全部走 deleted 索引）
        return recycleMapper.countAll();
    }

    private long toLong(Object v) {
        if (v == null) return 0L;
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0L; }
    }

    private void line(StringBuilder sb, String s) {
        sb.append(s).append('\n');
    }
}
