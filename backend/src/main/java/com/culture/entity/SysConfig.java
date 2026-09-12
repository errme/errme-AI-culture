package com.culture.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.Date;

/**
 * 系统配置项（表 {@code sys_config}，由后台「系统设置」页维护）。
 *
 * <p>把原先只能改 {@code application.yml} 再重启的可变项（站点信息、验证码策略、
 * 登录有效期、缓存开关、上传上限、接口限流阈值）搬到数据库，改完即时生效。</p>
 *
 * <p>读取方一律通过 {@link com.culture.service.ConfigService}，
 * 它会在内存里缓存整张表并在写入后刷新，避免每次请求都查库。</p>
 *
 * @see com.culture.service.ConfigService
 */
@Data
public class SysConfig {

    private Long id;

    /** 配置键（唯一），业务代码按它读取 */
    private String configKey;

    /** 当前值，统一按字符串存储 */
    private String configValue;

    /** 值类型：string / int / bool —— 决定转换方式与前端控件类型 */
    private String valueType;

    /** 分组：site / code / jwt / cache / upload / limit —— 前端按组分区 */
    private String configGroup;

    /** 中文名（后台显示） */
    private String label;

    /** 说明（后台显示在输入框下方） */
    private String description;

    /** 出厂默认值，供「恢复默认」使用 */
    private String defaultValue;

    /** 1 = 改完需要重启才生效 */
    private Integer restartRequired;

    /**
     * int 型的允许范围（来自数据库，不在代码里写死）。
     * 后端校验与前端提示共用同一份数据，改范围只需要改数据库。
     * NULL 表示该方向不限。
     */
    private Long minValue;

    private Long maxValue;

    /** 组内排序 */
    private Integer sort;

    private Date updatedAt;

    private Long updatedBy;

    /**
     * 非表字段：最后修改人的用户名。
     * 由 Mapper 关联查询带出，仅用于后台列表展示（不参与更新）。
     */
    @JsonIgnore
    private String updatedByName;
}
