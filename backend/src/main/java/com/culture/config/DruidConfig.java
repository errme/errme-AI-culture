package com.culture.config;


import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.support.http.StatViewServlet;
import com.alibaba.druid.support.http.WebStatFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.servlet.ServletRegistration;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class DruidConfig {

    /**
     *   将自定义的 Druid数据源添加到容器中，不再让 Spring Boot 自动创建
     *   绑定全局配置文件中的 druid 数据源属性到 com.alibaba.druid.pool.DruidDataSource从而让它们生效
     *   @ConfigurationProperties(prefix = "spring.datasource")：作用就是将 全局配置文件中
     *   前缀为 spring.datasource的属性值注入到 com.alibaba.druid.pool.DruidDataSource 的同名参数中
     */
    @ConfigurationProperties(prefix = "spring.datasource")
    @Bean
    public DataSource druidDataSource(){
        return new DruidDataSource();
    }

    /**
     * Druid 监控页（/druid/*）。
     *
     * 安全修复（重要）：原来这里**硬编码 admin/123456 且 allow=""（允许所有 IP）**，
     * 而 WebSecurityConfig 又把 /druid/** 放进 permitAll —— 等于任何能访问站点的人
     * 都能用仓库里的口令登录监控页，看到连接池与 SQL 统计。现在改为：
     *   1) 默认**不注册**该 Servlet（app.druid.stat.enabled=false），生产环境不暴露；
     *   2) 需要排查时用环境变量临时开启，并强制提供自定义口令
     *      （DRUID_STAT_USER / DRUID_STAT_PASSWORD），不再用弱口令；
     *   3) 默认只允许本机访问（app.druid.stat.allow=127.0.0.1），要放开必须显式配置。
     */
    @Bean
    public ServletRegistrationBean<StatViewServlet> statViewServlet(
            @Value("${app.druid.stat.enabled:false}") boolean enabled,
            @Value("${app.druid.stat.user:}") String user,
            @Value("${app.druid.stat.password:}") String password,
            @Value("${app.druid.stat.allow:127.0.0.1}") String allow) {
        if (!enabled) {
            // 返回一个未映射到任何 URL 的注册（Spring Boot 要求该 Bean 存在时才注册 Servlet）
            ServletRegistrationBean<StatViewServlet> disabled =
                    new ServletRegistrationBean<>(new StatViewServlet(), "/__druid_disabled__/*");
            disabled.setEnabled(false);
            return disabled;
        }
        if (user == null || user.trim().isEmpty() || password == null || password.length() < 8) {
            throw new IllegalStateException(
                    "启用 Druid 监控页时必须通过 DRUID_STAT_USER / DRUID_STAT_PASSWORD 提供账号，且口令至少 8 位");
        }
        ServletRegistrationBean<StatViewServlet> bean = new ServletRegistrationBean<>(new StatViewServlet(), "/druid/*");
        HashMap<String, String> initParameters = new HashMap<>();
        initParameters.put("loginUsername", user.trim());
        initParameters.put("loginPassword", password);
        // 默认只允许本机；需要放开时显式配置 app.druid.stat.allow（例如内网网段）
        initParameters.put("allow", allow == null ? "127.0.0.1" : allow);
        bean.setInitParameters(initParameters);
        log.warn("[druid] 监控页已启用：/druid/*（allow={}），排查完请关闭 app.druid.stat.enabled", allow);
        return bean;
    }

    private static final Logger log = LoggerFactory.getLogger(DruidConfig.class);

    @Bean
    public FilterRegistrationBean webStatFilter(){
        FilterRegistrationBean bean = new FilterRegistrationBean();
        bean.setFilter(new WebStatFilter());

        Map<String,String> initParameters = new HashMap<>();

        initParameters.put("exclusions","*.js,*.css,/druid/*");

        // "/*" 表示过滤所有请求
        // bean.setUrlPatterns(Arrays.asList("/*"));
        return bean;
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.datasource.druid.filter.stat",name = "enabled")
    @ConditionalOnMissingBean
    public StatFilter statFilter(){
        return new StatFilter();
    }
}
