package com.culture.service;

import com.culture.util.PageList;

import java.util.List;
import java.util.Map;

/**
 * 回收站服务：列出已逻辑删除（deleted=1）的内容 + 彻底删除（物理删除）。
 *
 * <p>与既有「删除可撤销」能力的关系：</p>
 * <ul>
 *   <li>删除 = 逻辑删除（deleted=1），恢复接口已存在于各实体（/api/admin/xxx/restore）；</li>
 *   <li>本服务补上缺失的两块：<b>看得到</b>（回收站列表/角标）与<b>删得掉</b>（彻底删除）。</li>
 * </ul>
 *
 * <p>权限校验（必须是管理员）由控制层 {@code ApiAdminRecycleController} 负责，
 * Service 只管业务规则，失败一律抛 BusinessException（控制层转 400 业务错误，不会 500）。</p>
 */
public interface RecycleService {

    /**
     * 回收站角标：7 类实体 deleted=1 的条数，一条 SQL 查完（无 N+1）。
     *
     * @return 固定顺序的 LinkedHashMap：culture / category / tag / announcement / sentence / comment / user
     */
    Map<String, Object> counts();

    /**
     * 回收站分页列表。
     *
     * @param type     实体类型：culture|category|tag|announcement|sentence|comment|user；其它值抛 BusinessException
     * @param keyword  模糊关键词（按各实体的名称/内容字段匹配；内部用 SearchUtil 截断 + LIKE 转义）
     * @param page     页码，&lt;1 或 null 时按 1
     * @param pageSize 每页条数，null/&lt;1 时按 10，&gt;100 时按 100 兜底
     * @return PageList：total=满足条件的总数，rows=归一化后的行（id/title/summary/extra/createTime/deletedTime）
     */
    PageList list(String type, String keyword, Integer page, Integer pageSize);

    /**
     * 彻底删除（物理删除）。只删「已逻辑删除」的行，SQL 一律带 deleted=1。
     *
     * <p>级联清理：culture → biz_culture_tag(culture_id)；tag → biz_culture_tag(tag_id)；
     * user → sys_user_role(user_id)。</p>
     *
     * <p>用户保护（抛 BusinessException）：不能删当前登录账号自己；不能删掉最后一个
     * 「管理员」角色用户。其余情况正常物理删除。</p>
     *
     * @param type          实体类型，同 {@link #list}
     * @param ids           要彻底删除的 id 列表；为空/全非法或超过 200 条抛 BusinessException
     * @param currentUserId 当前登录用户 id（用于「不能删自己」判断，可空）
     * @return { count: 主表实际删除行数, relationCount: 级联清理的关联行数 }
     */
    Map<String, Object> purge(String type, List<Long> ids, Long currentUserId);
}
