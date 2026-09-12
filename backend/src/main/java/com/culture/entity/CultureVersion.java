package com.culture.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Date;

/**
 * 内容版本历史（对应 biz_culture_version 表）。
 *
 * <p>写入时机（全部在 {@link com.culture.service.CultureVersionService} 内）：</p>
 * <ol>
 *   <li><b>修改保存前</b>：把「修改前」的整条记录快照进来（新增内容不写）；</li>
 *   <li><b>回滚前</b>：把「当前内容」再快照一条，保证回滚本身可逆。</li>
 * </ol>
 *
 * <p>字段说明：</p>
 * <ul>
 *   <li>{@link #content} 是 longtext 全文，<b>只有单条版本详情接口返回</b>；
 *       版本列表接口不返回它，只返回 {@link #contentLength}（SQL 里用 CHAR_LENGTH(content) 计算）；</li>
 *   <li>{@link #operatorName} 是冗余保存的用户名，用户改名/删除后仍可追溯；</li>
 *   <li>{@link #contentLength} 是<b>非表字段</b>，仅列表查询填充。</li>
 * </ul>
 *
 * <p>为 null 的字段不参与序列化（NON_NULL）：这样版本列表的 JSON 里就只有
 * {id, cultureId, name, operatorName, createTime, contentLength} 这几项，不会出现一堆 null。</p>
 */
@Data
public class CultureVersion {

    private Long id;

    /** 所属内容 biz_culture.id */
    private Long cultureId;

    /** 快照时的标题 */
    private String name;

    /** 快照时的描述（列表接口不返回） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String description;

    /** 快照时的正文全文（longtext；列表接口不返回，只有单条版本详情返回） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String content;

    /** 快照时的封面 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String coverUrl;

    /** 快照时的分类 id */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long categoryId;

    /** 操作人 sys_user.id（定时任务/系统操作时为 null） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long operatorId;

    /** 操作人用户名（冗余保存） */
    private String operatorName;

    /**
     * 快照时间。
     * 格式与项目里其它时间字段（如 User.createTime）保持一致：yyyy-MM-dd HH:mm:ss、东八区，
     * 前端 new Date(...) / 字符串截取都能直接用。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    /** 非表字段：正文长度（列表 SQL 用 CHAR_LENGTH(content) 计算，避免把 longtext 全文传到前端） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long contentLength;
}
