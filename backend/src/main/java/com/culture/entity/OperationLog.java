package com.culture.entity;

import lombok.Data;

import java.util.Date;

/**
 * 后台操作日志实体（对应新库 sys_operation_log 表）。
 * 只记录「写操作」（POST/PUT/DELETE）与后台管理路径，查询类 GET 不记录，避免日志表被刷爆。
 */
@Data
public class OperationLog {

    private Long id;

    /** 操作人 sys_user.id */
    private Long userId;

    /** 操作人用户名（冗余保存，用户被删后仍可追溯） */
    private String username;

    /** 模块：culture/category/announcement/sentence/user/mail/tag/comment */
    private String module;

    /** 动作：save/delete/audit/upload */
    private String action;

    /** 目标对象 id（取不到则为空） */
    private String targetId;

    /** 可读摘要，如 culture.save#12 */
    private String detail;

    /** HTTP 方法 */
    private String method;

    /** 请求路径 */
    private String uri;

    private String ip;

    /** 1 成功 0 失败 */
    private Integer success;

    /** 耗时毫秒 */
    private Long costMs;

    private Date createTime;
}
