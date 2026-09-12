package com.culture.entity;

import lombok.Data;

import java.util.Date;

/**
 * 标签实体（对应新库 biz_tag 表）。
 *
 * <p>cultureCount 不是表字段，而是在列表接口里一次性统计出来回填的「内容数」，
 * 供前台/后台标签列表展示，避免前端对每个标签再发一次请求（N+1）。</p>
 */
@Data
public class Tag {

    private Long id;

    /** 标签名（唯一） */
    private String name;

    /** 英文标识（URL 用，可空） */
    private String slug;

    /** 排序（越小越前） */
    private Integer sort;

    /** 创建时间（新库 created_at 列） */
    private Date createTime;

    /** 逻辑删除：0 正常 1 删除 */
    private Integer deleted;

    /** 非表字段：该标签下的文化数量（列表接口回填） */
    private Integer cultureCount;

    /** 非表字段：批量按文化查标签时携带所属文化 id（不对外输出 JSON） */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long cultureId;
}
