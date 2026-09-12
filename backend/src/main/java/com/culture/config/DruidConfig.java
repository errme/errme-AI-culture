package com.culture.config;

import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Druid 数据源配置。
 *
 * <p><b>Spring Boot 3 升级说明（重要）：</b></p>
 * <p>原先这里还注册了 <code>StatViewServlet</code>（/druid/* 监控页）与
 * <code>WebStatFilter</code>。升级到 Spring Boot 3（Servlet 6 / {@code jakarta.servlet}）后，
 * 这两个类在 Druid 中<b>至今仍是 {@code javax.servlet} 实现</b>
 * （已核实 druid 1.2.24 jar 内的字节码引用的是 {@code javax/servlet/*}），
 * 容器启动时会直接抛 {@code NoClassDefFoundError}，因此必须移除。</p>
 *
 * <p>移除的代价很小：该监控页本来就是调试功能，且默认关闭
 * （{@code app.druid.stat.enabled: false}），生产环境不暴露。
 * 连接池本身（{@code DruidDataSource}）以及 stat / wall 过滤器都不依赖 Servlet API，
 * 因此完整保留，慢 SQL 统计与 SQL 注入防护能力不变。
 * 运行时可观测性由项目自己的 {@code /api/metrics} 与
 * {@code SlowSqlInterceptor}（MyBatis 插件）提供，不依赖 Druid 页面。</p>
 */
@Configuration
public class DruidConfig {

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

    @Bean
    @ConditionalOnProperty(prefix = "spring.datasource.druid.filter.stat", name = "enabled")
    @ConditionalOnMissingBean
    public StatFilter statFilter() {
        return new StatFilter();
    }
}
