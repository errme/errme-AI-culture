package com.culture.config;

import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.support.jakarta.StatViewServlet;
import com.alibaba.druid.support.jakarta.WebStatFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Druid 数据源 + 官方监控页（已接入后台）。
 *
 * <h3>为什么现在能重新接入（Spring Boot 3 的关键点）</h3>
 * <p>Druid 的 {@code com.alibaba.druid.support.http.StatViewServlet} 是 <b>javax.servlet</b> 实现，
 * 在 Spring Boot 3（jakarta.servlet）下挂载会直接抛 {@code NoClassDefFoundError} ——
 * 这正是上一轮升级时把监控页摘掉的原因。</p>
 *
 * <p>准备重新接入时实测发现：<b>Druid 自 1.2.25 起提供了 jakarta 变体</b>，放在另一个包下：</p>
 * <pre>
 *   javax  版（SB2）：com.alibaba.druid.support.http.StatViewServlet
 *   jakarta 版（SB3）：com.alibaba.druid.support.jakarta.StatViewServlet
 * </pre>
 * <p>因此把 Druid 从 1.2.24 升到 <b>1.2.28</b> 后，官方监控页可以原样挂载。本类用的就是 jakarta 版。</p>
 *
 * <h3>访问入口（全部可配置，均不写死）</h3>
 * <table border="1">
 *   <tr><th>配置项</th><th>默认</th><th>说明</th></tr>
 *   <tr><td>{@code app.druid.stat.enabled}</td><td>{@code true}</td>
 *       <td>是否注册监控页；关掉时 Bean 仍在但不映射任何 URL</td></tr>
 *   <tr><td>{@code app.druid.stat.path}</td><td>{@code /druid/*}</td><td>访问路径</td></tr>
 *   <tr><td>{@code app.druid.stat.user}</td><td>环境变量 {@code DRUID_STAT_USER} 或 {@code admin}</td>
 *       <td>监控页账号</td></tr>
 *   <tr><td>{@code app.druid.stat.password}</td>
 *       <td>环境变量 {@code DRUID_STAT_PASSWORD}</td>
 *       <td><b>必须显式提供且至少 8 位</b>，否则启动直接失败（避免弱口令进仓库）</td></tr>
 *   <tr><td>{@code app.druid.stat.allow}</td><td>本机 + 内网网段</td><td>来源 IP 白名单</td></tr>
 *   <tr><td>{@code app.druid.stat.reset-enable}</td><td>{@code false}</td>
 *       <td>是否允许在页面上重置统计数据</td></tr>
 *   <tr><td>{@code app.druid.stat.uri-monitor}</td><td>{@code true}</td>
 *       <td>是否开启 URI 监控（WebStatFilter）</td></tr>
 * </table>
 *
 * <h3>安全说明</h3>
 * <p>监控页有<b>两层</b>保护，且都来自配置而非硬编码：</p>
 * <ol>
 *   <li>Druid 自带登录（账号见上；口令只从环境变量读）；</li>
 *   <li>来源 IP 白名单（默认只允许本机与内网网段）。</li>
 * </ol>
 * <p>它同时暴露 SQL 统计等敏感信息，因此<b>生产环境建议保持默认关闭</b>；
 * 需要排查时临时用环境变量开启，排查完关掉。</p>
 */
@Configuration
public class DruidConfig {

    private static final Logger log = LoggerFactory.getLogger(DruidConfig.class);

    /** 环境变量名：监控页口令与账号。放在常量里，避免口令写进仓库。 */
    public static final String ENV_PASSWORD = "DRUID_STAT_PASSWORD";
    public static final String ENV_USER = "DRUID_STAT_USER";

    private static final String DEFAULT_PATH = "/druid/*";

    /**
     * 将自定义的 Druid 数据源添加到容器中，不再让 Spring Boot 自动创建。
     * {@code @ConfigurationProperties(prefix = "spring.datasource")} 把全局配置文件中
     * 前缀为 spring.datasource 的属性值注入到 {@link DruidDataSource} 的同名参数中。
     */
    @ConfigurationProperties(prefix = "spring.datasource")
    @Bean
    public DataSource druidDataSource() {
        return new DruidDataSource();
    }

    /**
     * Druid 官方监控页（jakarta 版 Servlet）。
     *
     * <p>关闭时返回一个 {@code enabled=false} 的注册（不映射任何 URL），
     * 这样 Bean 仍然存在、容器能正常装配，但外部访问不到。</p>
     */
    @Bean
    public ServletRegistrationBean<StatViewServlet> druidStatViewServlet(
            @Value("${app.druid.stat.enabled:true}") boolean enabled,
            @Value("${app.druid.stat.path:" + DEFAULT_PATH + "}") String path,
            @Value("${app.druid.stat.user:${" + ENV_USER + ":admin}}") String user,
            @Value("${app.druid.stat.password:${" + ENV_PASSWORD + ":}}") String password,
            @Value("${app.druid.stat.allow:127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,::1}") String allow,
            @Value("${app.druid.stat.reset-enable:false}") boolean resetEnable) {

        if (!enabled) {
            ServletRegistrationBean<StatViewServlet> disabled =
                    new ServletRegistrationBean<>(new StatViewServlet(), "/__druid_disabled__/*");
            disabled.setEnabled(false);
            log.info("[druid] 监控页未启用（app.druid.stat.enabled=false）");
            return disabled;
        }

        // 口令必须显式提供且足够长 —— 缺省一律拒绝启动，
        // 避免「默认口令跟仓库一起提交」这类问题重演。
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalStateException(
                    "启用 Druid 监控页必须提供口令：设置环境变量 " + ENV_PASSWORD
                            + "（或配置 app.druid.stat.password），至少 8 位");
        }
        if (password.trim().length() < 8) {
            throw new IllegalStateException("Druid 监控页口令至少 8 位（当前 "
                    + password.trim().length() + " 位）");
        }

        ServletRegistrationBean<StatViewServlet> bean =
                new ServletRegistrationBean<>(new StatViewServlet(), path);
        Map<String, String> init = new HashMap<>();
        init.put("loginUsername", user);
        init.put("loginPassword", password.trim());
        init.put("allow", allow);
        // 默认不给「重置统计」按钮：远程改动运维数据没有实际必要
        init.put("resetEnable", String.valueOf(resetEnable));
        bean.setInitParameters(init);
        bean.setName("druidStatView");

        log.warn("[druid] 监控页已启用：{}（账号={}，IP 白名单={}）—— 该页面会暴露 SQL 统计等"
                + "敏感信息，排查完请关闭 app.druid.stat.enabled", path, user, allow);
        return bean;
    }

    /**
     * URI 监控过滤器（jakarta 版），让监控页里能看到「哪些接口慢」。
     *
     * <p>排除静态资源与监控页自身，避免噪音与自观测。</p>
     */
    @Bean
    public FilterRegistrationBean<WebStatFilter> druidWebStatFilter(
            @Value("${app.druid.stat.enabled:true}") boolean enabled,
            @Value("${app.druid.stat.uri-monitor:true}") boolean uriMonitor,
            @Value("${app.druid.stat.path:" + DEFAULT_PATH + "}") String druidPath,
            @Value("${app.druid.stat.uri-exclusions:*.js,*.css,*.gif,*.jpg,*.jpeg,*.png,*.ico,*.woff,*.woff2,*.ttf}") String exclusions) {

        FilterRegistrationBean<WebStatFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new WebStatFilter());
        if (!enabled || !uriMonitor) {
            bean.setEnabled(false);
            return bean;
        }

        bean.addUrlPatterns("/*");
        Map<String, String> init = new HashMap<>();
        // 监控页自身也要排除，否则统计里会混入监控页请求
        init.put("exclusions", exclusions + "," + druidPath);
        // 无状态 Token 方案：不需要会话统计，关掉可减少开销
        init.put("sessionStatEnable", "false");
        init.put("profileEnable", "false");
        bean.setInitParameters(init);
        bean.setName("druidWebStat");
        return bean;
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.datasource.druid.filter.stat", name = "enabled")
    @ConditionalOnMissingBean
    public StatFilter statFilter() {
        return new StatFilter();
    }
}
