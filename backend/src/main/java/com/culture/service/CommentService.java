package com.culture.service;

import com.culture.entity.Comment;
import com.culture.query.CommentQuery;
import com.culture.util.PageList;

import java.util.List;
import java.util.Map;

/**
 * 评论服务：前台提交/展示（先审后发）+ 后台审核管理。
 */
public interface CommentService {

    /**
     * 前台提交评论（匿名或登录）。
     * content 必须经 HtmlSanitizer 清洗（上限 1000 字）后入库，nickname 取纯文本限 20 字，
     * email 校验失败置空；status 固定 0（待审）；同 IP 60 秒内只允许 1 条。
     *
     * @return { id, status }
     */
    Map<String, Object> submit(Comment comment, String ip, String userAgent);

    /** 某文化下已通过的评论（两级树）：{ total, comments } */
    Map<String, Object> listApproved(Long cultureId);

    /** 后台分页（status + keyword + cultureId） */
    PageList adminPage(CommentQuery query);

    /** 审核：status 1 通过 / 2 拒绝，记录审核人与时间 */
    void audit(Long id, int status, Long adminId);

    /** 逻辑删除 */
    void delete(Long id);

    /** 待审数量 */
    long pendingCount();

    // ===================== 后台批量操作（增量追加） =====================

    /**
     * 批量审核：status 1 通过 / 2 拒绝（与单条 {@link #audit} 语义一致），
     * 统一记录审核人与审核时间。
     *
     * @param ids     评论 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException
     * @param adminId 审核人 sys_user.id
     * @return 实际更新条数（不存在的 id 会被忽略）
     */
    int batchAudit(List<Long> ids, int status, Long adminId);

    /**
     * 批量逻辑删除（deleted=1）。
     *
     * @param ids 评论 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException
     * @return 实际更新条数
     */
    int batchDelete(List<Long> ids);

    // ===================== 重新过词表（增量追加） =====================

    /**
     * 对一批已存在的「待审核」评论重新过一遍启用敏感词表：
     * 命中 action=1（直接拒绝）的词 → 置为已拒绝（status=2），并记录审核人与审核时间；
     * 命中 action=2 的不做处理（评论本来就是待审）。已通过/已拒绝/已删除的评论一律不动。
     *
     * <p>词表为空或加载异常时不误判（matchAction 返回「无命中」），即不会因为词表故障而拒绝评论。</p>
     *
     * @param ids     评论 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException（控制层转 400）
     * @param adminId 操作人 sys_user.id（写入 audited_by）
     * @return { checked: 实际检查的待审评论数, rejected: 实际置为拒绝的条数 }
     */
    Map<String, Object> recheckSensitive(List<Long> ids, Long adminId);

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /**
     * 恢复单条被逻辑删除的评论（deleted 1 → 0）；
     * status / audited_by / audited_at 等审核信息保持原样，不需要重新审核。
     * 权限校验与删除一致，由控制层（/api/admin/comment/restore，要求管理员）负责。
     *
     * @param id 评论 id；为 null 时抛 BusinessException（控制层转 400）
     * @return 实际影响行数；0 表示该 id 不存在或本来就没被删除
     */
    int restore(Long id);

    /**
     * 批量恢复被逻辑删除的评论。
     *
     * @param ids 评论 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException（控制层转 400）
     * @return 实际影响行数
     */
    int restoreBatch(List<Long> ids);
}
