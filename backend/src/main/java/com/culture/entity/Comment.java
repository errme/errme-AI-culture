package com.culture.entity;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 评论实体（对应新库 biz_comment 表，先审后发：status 0 待审 1 通过 2 拒绝）。
 *
 * <p>content 保存的是<b>已用 HtmlSanitizer 白名单清洗</b>的 HTML，前端可直接渲染。
 * nickname/email 属于不可信输入，展示时按纯文本处理。</p>
 */
@Data
public class Comment {

    private Long id;

    /** 所属文化 biz_culture.id */
    private Long cultureId;

    /** 父评论 id，0 为顶层 */
    private Long parentId;

    /** 登录用户 id（匿名可空） */
    private Long userId;

    /** 昵称（匿名时必填，已按纯文本截断 20 字） */
    private String nickname;

    /** 邮箱（不公开展示） */
    private String email;

    /** 评论内容（已白名单清洗的 HTML） */
    private String content;

    /** 0 待审 1 通过 2 拒绝 */
    private Integer status;

    /** 来源 IP */
    private String ip;

    /** 浏览器 UA（截断保存） */
    private String userAgent;

    private Date createTime;

    /** 审核时间 */
    private Date auditedAt;

    /** 审核人 sys_user.id */
    private Long auditedBy;

    /** 逻辑删除：0 正常 1 删除 */
    private Integer deleted;

    // ==================== 非表字段（查询回填） ====================

    /** 所属文化名称（后台列表 join biz_culture 得到） */
    private String cultureName;

    /** 子评论（前台两级树；为 null 时不出现在 JSON 中） */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private List<Comment> children;
}
