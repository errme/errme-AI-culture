package com.culture.mapper;

import com.culture.entity.OperationLog;
import com.culture.query.OperationLogQuery;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 操作日志 Mapper（新库 sys_operation_log，注解 SQL 风格）。
 * 日志只增不改不删：insert + 后台分页查询。
 *
 * <p><b>关于 {@code where 1 = 1}（Spring Boot 3 升级时踩到的坑）：</b>
 * 下面两个查询原先写的是 {@code from sys_operation_log l where 1 = 1}
 * 再接若干 {@code <if> and ...}。这是 MyBatis 动态 SQL 里常见的「占位锚点」写法，
 * 但在 Druid 从 1.1.21 升到 1.2.24 之后会被 <b>wall 过滤器直接拦下</b>：</p>
 * <pre>
 *   sql injection violation, dbType mysql, druid-version 1.2.24,
 *   select alway true condition not allow : select count(*) from sys_operation_log l where 1 = 1
 * </pre>
 * <p>表现为「操作日志页面报 401/异常、打不开」，很容易误判成鉴权问题。</p>
 *
 * <p>正确做法不是放宽 wall（{@code selectWhereAlwayTrueCheck=false} 会削弱 SQL 注入防护），
 * 而是用 MyBatis 的 {@code <where>} 标签：它会自动去掉紧随其后的多余 {@code AND}，
 * 条件全为空时也不会生成 WHERE 子句 —— 既没有恒真条件，语义还更准确。</p>
 */
@Mapper
public interface OperationLogMapper {

    /** 写入一条操作日志 */
    @Insert("insert into sys_operation_log(user_id, username, module, action, target_id, detail, method, uri, ip, success, cost_ms, created_at) " +
            "values(#{userId}, #{username}, #{module}, #{action}, #{targetId}, #{detail}, #{method}, #{uri}, #{ip}, #{success}, #{costMs}, now())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OperationLog log);

    /** 后台分页：总数 */
    @Select("<script>" +
            "select count(*) from sys_operation_log l " +
            "<where>" +
            "<if test=\"module != null and module != ''\"> and l.module = #{module} </if>" +
            "<if test=\"keyword != null and keyword != ''\">" +
            " and (l.username like concat('%', #{keyword}, '%') or l.detail like concat('%', #{keyword}, '%'))" +
            "</if>" +
            "</where>" +
            "</script>")
    Long queryTotal(OperationLogQuery query);

    /** 后台分页：数据（按 id 倒序） */
    @Select("<script>" +
            "select l.id, l.user_id userId, l.username, l.module, l.action, l.target_id targetId, l.detail, " +
            "l.method, l.uri, l.ip, l.success, l.cost_ms costMs, l.created_at createTime " +
            "from sys_operation_log l " +
            "<where>" +
            "<if test=\"module != null and module != ''\"> and l.module = #{module} </if>" +
            "<if test=\"keyword != null and keyword != ''\">" +
            " and (l.username like concat('%', #{keyword}, '%') or l.detail like concat('%', #{keyword}, '%'))" +
            "</if>" +
            "</where>" +
            " order by l.id desc limit #{offset}, #{pageSize}" +
            "</script>")
    List<OperationLog> queryData(OperationLogQuery query);
}
