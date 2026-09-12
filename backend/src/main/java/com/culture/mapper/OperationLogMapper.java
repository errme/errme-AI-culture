package com.culture.mapper;

import com.culture.entity.OperationLog;
import com.culture.query.OperationLogQuery;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 操作日志 Mapper（新库 sys_operation_log，注解 SQL 风格）。
 * 日志只增不改不删：insert + 后台分页查询。
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
            "select count(*) from sys_operation_log l where 1 = 1 " +
            "<if test=\"module != null and module != ''\"> and l.module = #{module} </if>" +
            "<if test=\"keyword != null and keyword != ''\">" +
            " and (l.username like concat('%', #{keyword}, '%') or l.detail like concat('%', #{keyword}, '%'))" +
            "</if>" +
            "</script>")
    Long queryTotal(OperationLogQuery query);

    /** 后台分页：数据（按 id 倒序） */
    @Select("<script>" +
            "select l.id, l.user_id userId, l.username, l.module, l.action, l.target_id targetId, l.detail, " +
            "l.method, l.uri, l.ip, l.success, l.cost_ms costMs, l.created_at createTime " +
            "from sys_operation_log l where 1 = 1 " +
            "<if test=\"module != null and module != ''\"> and l.module = #{module} </if>" +
            "<if test=\"keyword != null and keyword != ''\">" +
            " and (l.username like concat('%', #{keyword}, '%') or l.detail like concat('%', #{keyword}, '%'))" +
            "</if>" +
            " order by l.id desc limit #{offset}, #{pageSize}" +
            "</script>")
    List<OperationLog> queryData(OperationLogQuery query);
}
