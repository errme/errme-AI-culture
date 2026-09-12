package com.culture.query;

import lombok.Data;

/**
 * 操作日志查询条件。
 * module：模块名（culture/category/...）；keyword：模糊匹配 username / detail。
 */
@Data
public class OperationLogQuery extends BaseQuery {

    /** 模块（为空表示全部） */
    private String module;

    /** 关键词：匹配操作人或摘要 */
    private String keyword;
}
