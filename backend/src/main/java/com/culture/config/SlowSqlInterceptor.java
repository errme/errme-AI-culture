package com.culture.config;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 慢 SQL 上报（MyBatis 插件版）。
 *
 * <p><b>与 Druid 的关系</b>：Druid 已配了 {@code druid.stat.slowSqlMillis=1000;logSlowSql=true}，
 * 会在 SQL 超过 1 秒时打日志。但它只有「日志」：没有计数、没有最大值，也没法被 HTTP 抓取。
 * 本项目是内容站，1 秒阈值对本地分页/列表查询偏宽松，排查「偶发变慢」时还需要知道
 * 「最近一次是哪条语句、花了多久」，所以这里补一层轻量上报：</p>
 * <ol>
 *   <li>超过 {@code app.slow-sql.threshold-ms}（默认 500ms）时打一条 WARN，
 *       包含 statement id、耗时、SQL（占位符形式，已压缩空白并截断）；</li>
 *   <li>累计 总次数 / 最大耗时 / 最近一次语句与耗时，由
 *       {@link com.culture.api.MetricsController} 输出成 Prometheus 文本
 *       （{@code culture_slow_sql_*}），可被监控抓取。</li>
 * </ol>
 *
 * <p><b>零侵入</b>：只做「计时 + 记日志 + 计数」，不改 SQL、不改参数、不吞异常
 * （原调用照常执行，异常原样抛出）。{@code app.slow-sql.enabled=false} 时插件仍然注册，
 * 但直接放行、不做任何计时。</p>
 *
 * <p>计数器是进程内的（不跨实例聚合）：单实例部署够用；多实例时各实例各自输出，
 * 由 Prometheus 按 instance 维度聚合，符合指标系统的一般做法。</p>
 */
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class SlowSqlInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlInterceptor.class);

    private final boolean enabled;
    private final long thresholdMs;
    private final int maxSqlLength;

    /** 总慢 SQL 次数（进程内累计，不重置） */
    private final LongAdder slowCount = new LongAdder();
    /** 最慢一次耗时（毫秒） */
    private final AtomicLong maxCostMs = new AtomicLong(0L);
    /** 最近一次慢 SQL 的耗时与语句（volatile：写线程与 /api/metrics 读线程不同） */
    private volatile long lastCostMs = 0L;
    private volatile String lastStatement = "";

    public SlowSqlInterceptor(@Value("${app.slow-sql.enabled:true}") boolean enabled,
                              @Value("${app.slow-sql.threshold-ms:500}") long thresholdMs,
                              @Value("${app.slow-sql.max-sql-length:400}") int maxSqlLength) {
        this.enabled = enabled;
        this.thresholdMs = thresholdMs > 0 ? thresholdMs : 500L;
        this.maxSqlLength = maxSqlLength > 0 ? maxSqlLength : 400;
        log.info("[slow-sql] MyBatis 慢 SQL 上报：enabled={}, threshold={}ms", enabled, this.thresholdMs);
    }

    // ===================== 供 MetricsController 读取 =====================

    public boolean isEnabled() {
        return enabled;
    }

    public long getThresholdMs() {
        return thresholdMs;
    }

    public long getSlowCount() {
        return slowCount.sum();
    }

    public long getMaxCostMs() {
        return maxCostMs.get();
    }

    public long getLastCostMs() {
        return lastCostMs;
    }

    public String getLastStatement() {
        return lastStatement;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!enabled) {
            return invocation.proceed();
        }
        long begin = System.currentTimeMillis();
        try {
            return invocation.proceed();
        } finally {
            long cost = System.currentTimeMillis() - begin;
            if (cost >= thresholdMs) {
                record(invocation, cost);
            }
        }
    }

    private void record(Invocation invocation, long cost) {
        String statement = "unknown";
        String sql = "";
        try {
            Object[] args = invocation.getArgs();
            if (args != null && args.length > 0 && args[0] instanceof MappedStatement) {
                MappedStatement ms = (MappedStatement) args[0];
                statement = ms.getId();
                Object parameter = args.length > 1 ? args[1] : null;
                BoundSql boundSql = ms.getBoundSql(parameter);
                sql = boundSql == null ? "" : abbreviate(boundSql.getSql());
            }
        } catch (Exception e) {
            // 取语句信息失败不影响主流程（动态 SQL 可能需要额外上下文）
            log.debug("[slow-sql] 获取语句信息失败：{}", e.toString());
        }

        slowCount.increment();
        maxCostMs.accumulateAndGet(cost, Math::max);
        lastCostMs = cost;
        lastStatement = statement;

        log.warn("[slow-sql] {}ms >= {}ms | {} | {}", cost, thresholdMs, statement, sql);
    }

    /** 压缩空白 + 截断，避免一条 SQL 把日志刷爆 */
    private String abbreviate(String sql) {
        if (sql == null) {
            return "";
        }
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() <= maxSqlLength ? flat : flat.substring(0, maxSqlLength) + "...";
    }

    @Override
    public Object plugin(Object target) {
        // 只包装 Executor（在 SQL 执行路径上），其余原样返回
        return target instanceof Executor ? Plugin.wrap(target, this) : target;
    }

    @Override
    public void setProperties(Properties properties) {
        // 配置走 Spring @Value，不使用 MyBatis 的 properties（与项目其它组件一致）
    }
}
