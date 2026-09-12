package com.culture.mapper;

import com.culture.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 系统配置 Mapper（表 {@code sys_config}）。
 *
 * <p><b>踩坑记录</b>：MySQL 5.7 里 {@code MAXVALUE} 是保留字（分区语法
 * {@code VALUES LESS THAN MAXVALUE}），而标识符大小写不敏感 —— 因此别名写成
 * {@code c.max_value maxValue} 会直接 SQL 语法错误，必须用反引号写成
 * {@code c.max_value `maxValue`}。{@code minValue} 不受影响。</p>
 *
 * <p>配置项总量很小（十余条），读取一律走
 * {@link com.culture.service.ConfigService} 的内存缓存，
 * 因此这里只提供「全量查」与「按 key 更新」两个最小操作，不做分页。</p>
 */
@Mapper
public interface SysConfigMapper {

    /**
     * 全量查询（按分组与排序返回），供 Service 载入缓存与后台列表使用。
     *
     * <p>关联 sys_user 带出最后修改人用户名，便于后台看到「谁改的」。</p>
     */
    @Select("select c.id, c.config_key configKey, c.config_value configValue, " +
            "c.value_type valueType, c.config_group configGroup, c.label, c.description, " +
            "c.default_value defaultValue, c.restart_required restartRequired, " +
            "c.min_value minValue, c.max_value `maxValue`, c.sort, " +
            "c.updated_at updatedAt, c.updated_by updatedBy, u.username updatedByName " +
            "from sys_config c " +
            "left join sys_user u on u.id = c.updated_by " +
            "order by c.config_group, c.sort, c.id")
    List<SysConfig> findAll();

    /** 按 key 查询单条（Service 回源兜底用） */
    @Select("select id, config_key configKey, config_value configValue, value_type valueType, " +
            "config_group configGroup, label, description, default_value defaultValue, " +
            "restart_required restartRequired, min_value minValue, max_value `maxValue`, " +
            "sort, updated_at updatedAt, updated_by updatedBy " +
            "from sys_config where config_key = #{key}")
    SysConfig findByKey(@Param("key") String key);

    /**
     * 更新一个键的值。
     *
     * <p>只改 config_value / updated_at / updated_by 三列 ——
     * label、description、default_value 等元信息由 SQL 脚本维护，后台不可改，
     * 避免运维把「说明」也改乱。</p>
     *
     * @return 实际影响行数（0 表示该 key 不存在）
     */
    @Update("update sys_config set config_value = #{value}, updated_at = now(), updated_by = #{operatorId} " +
            "where config_key = #{key}")
    int updateValue(@Param("key") String key,
                    @Param("value") String value,
                    @Param("operatorId") Long operatorId);
}
