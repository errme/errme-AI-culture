package com.culture.query;

import lombok.Data;

/**
 * 评论后台查询条件。
 * status：-1（或 null）= 全部；0 待审；1 通过；2 拒绝。
 * keyword：模糊匹配 content / nickname。
 */
@Data
public class CommentQuery extends BaseQuery {

    /** 审核状态：-1 全部 */
    private Integer status;

    /** 关键词：匹配评论内容或昵称 */
    private String keyword;

    /** 所属文化（可选，用于只看某个文化的评论） */
    private Long cultureId;
}
