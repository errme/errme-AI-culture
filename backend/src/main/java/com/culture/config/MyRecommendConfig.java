package com.culture.config;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.mahout.cf.taste.impl.model.jdbc.MySQLJDBCDataModel;
import org.apache.mahout.cf.taste.model.DataModel;
import org.apache.mahout.cf.taste.model.JDBCDataModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.SQLException;

/**
 * Mahout 协同过滤推荐数据源配置。
 * 新库 culture_v2：推荐模型基于 biz_like（用户-文化-偏好值）构建。
 * 账号/库名从 application.yml 的 spring.datasource 读取（带默认值便于本地运行）。
 */
@Configuration
public class MyRecommendConfig {

    // 与 application.yml 的 spring.datasource 保持一致；本地默认 root/123456/culture_v2
    @Value("${spring.datasource.url:jdbc:mysql://127.0.0.1:3306/culture_v2}")
    private String dbUrl;

    @Value("${spring.datasource.username:root}")
    private String dbuser;

    @Value("${spring.datasource.password:123456}")
    private String dbpwd;

    //步骤1：创建DataModel模型，基于数据库的JDBCDataModel。
    //步骤2：采用欧几里得、皮尔逊等算法计算相似度。
    //步骤3：构建推荐器，基于用户或基于内容进行推荐。
    //步骤4：将推荐出来的商品id补全其他数据返回给用户展示。

    /**
     * 基于数据库的 DataModel 模型。
     * 参数：表名 biz_like；用户列 user_id；目标列 target_id；偏好值列 value；时间戳列 created_at。
     */
    @Bean
    public DataModel getMySQLDataModel() throws SQLException {
        // 从 jdbc:mysql://host:port/db 连接串中解析出库名，避免重复配置。
        // 注意：不能直接取最后一个 '/'，因为 serverTimezone=Asia/Shanghai 参数里也含 '/'
        int queryIdx = dbUrl.indexOf('?');
        int dbStart = dbUrl.lastIndexOf('/', queryIdx > 0 ? queryIdx - 1 : dbUrl.length() - 1);
        String dbName = dbUrl.substring(dbStart + 1, queryIdx > 0 ? queryIdx : dbUrl.length());

        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setServerName("localhost");
        dataSource.setUser(dbuser);
        dataSource.setPassword(dbpwd);
        dataSource.setDatabaseName(dbName);
        dataSource.setServerTimezone("UTC");
        JDBCDataModel dataModel = new MySQLJDBCDataModel(dataSource, "biz_like", "user_id", "target_id", "value", "created_at");
        return dataModel;
    }
}
