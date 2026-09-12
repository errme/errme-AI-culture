package com.culture.entity;

import lombok.Data;

import java.util.Date;

/**
 * 评论敏感词实体（对应库表 biz_sensitive_word）。
 *
 * <p>命中处理 action：<b>1 = 直接拒绝</b>（评论提交直接返回 400，不入库）、
 * <b>2 = 转待审核</b>（评论正常入库但强制 status=0，并在响应里给温和提示）。
 * 判定方式是「HtmlSanitizer 清洗后的纯文本 + 大小写不敏感包含匹配」，
 * 详见 {@link com.culture.service.impl.SensitiveWordServiceImpl#matchAction(String)}。</p>
 *
 * <p>逻辑删除：deleted 0 正常 / 1 删除。word 上有唯一键 uk_word，
 * 因此被逻辑删除的同名词仍然占用该名称（后台新增同名词时会「复活」旧行而不是插入新行）。</p>
 */
@Data
public class SensitiveWord {

    private Long id;

    /** 敏感词（最长 100 字符；匹配时统一转小写） */
    private String word;

    /** 命中处理：1 直接拒绝 2 转待审核 */
    private Integer action;

    /** 是否启用：1 启用 0 停用（只有启用且未删除的词参与匹配） */
    private Integer enabled;

    /** 备注（后台自己看，说明为什么加这个词） */
    private String remark;

    /** 创建时间（库列 created_at） */
    private Date createTime;

    /** 逻辑删除：0 正常 1 删除 */
    private Integer deleted;
}
