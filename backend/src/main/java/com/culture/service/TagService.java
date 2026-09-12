package com.culture.service;

import com.culture.entity.Tag;
import com.culture.util.PageList;

import java.util.List;
import java.util.Map;

/**
 * 标签服务：标签的增删改查 + 文化-标签关联维护。
 */
public interface TagService {

    /** 全部未删除标签（含每个标签的内容数 cultureCount） */
    List<Tag> queryAll();

    /**
     * 新增（无 id）或更新（有 id）。
     * 名称重复（含被逻辑删除的同名标签）时抛 BusinessException("标签已存在")。
     *
     * @return { id, name, created }
     */
    Map<String, Object> save(Tag tag);

    /** 删除标签（同时解除文化关联） */
    void delete(Long id);

    /** 某个文化挂的标签 */
    List<Tag> tagsOfCulture(Long cultureId);

    /** 重设某个文化的标签（先删后插，事务） */
    void setCultureTags(Long cultureId, List<Long> tagIds);

    /** 批量取回多个文化的标签：cultureId -> tags（供列表页一次取回，避免 N+1） */
    Map<String, List<Tag>> tagsOfCultures(List<Long> cultureIds);

    /** 某标签下的文化分页（前台公开接口用） */
    PageList culturesOfTag(Long tagId, Integer page, Integer pageSize);

    // ===================== 后台批量操作（增量追加） =====================

    /**
     * 批量删除标签（逻辑删除，并同时解除文化关联，与单条 {@link #delete} 语义一致）。
     *
     * @param ids 标签 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException
     * @return 实际删除条数
     */
    int batchDelete(List<Long> ids);

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /**
     * 恢复单条被逻辑删除的标签（deleted 1 → 0）。
     *
     * <p><b>注意</b>：删除标签时 biz_culture_tag 的文化关联是物理删除的，数据库里已无痕迹，
     * 因此恢复标签<b>不会</b>、也无法恢复原来的文化关联；恢复后标签重新出现在标签列表中，
     * 内容数为 0，需要管理员重新绑定（与单条 {@link #delete} 的「先解绑再逻辑删除」对称）。
     * 权限校验与删除一致，由控制层（/api/admin/tag/restore，要求管理员）负责。</p>
     *
     * @param id 标签 id；为 null 时抛 BusinessException（控制层转 400）
     * @return 实际影响行数；0 表示该 id 不存在或本来就没被删除
     */
    int restore(Long id);

    /**
     * 批量恢复被逻辑删除的标签（同样不恢复已物理删除的文化关联）。
     *
     * @param ids 标签 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException（控制层转 400）
     * @return 实际影响行数
     */
    int restoreBatch(List<Long> ids);

    // ===================== 标签合并 / 改名（增量追加） =====================

    /**
     * 把「源标签」合并进「目标标签」（一个事务内完成）：
     * <ol>
     *   <li>校验两个标签都存在、都未删除、且不是同一个（否则抛 BusinessException → 400）；</li>
     *   <li>把 biz_culture_tag 里 tag_id = sourceId 的关联<b>改挂</b>到 targetId；
     *       文化已经同时挂过目标标签的（uk_culture_tag 冲突）不重复插入，直接删掉源关联（合并）；</li>
     *   <li>源标签逻辑删除（deleted = 1）。</li>
     * </ol>
     *
     * <p>失败整体回滚（含关联改挂）。语义与 {@link #delete} 一致：合并后源标签从列表消失，
     * 且不提供「反合并」（关联已改挂到目标标签，无法区分原本属于谁）。</p>
     *
     * <p>计数口径：moved 与 merged 由「改挂前/改挂后的关联数」相减得到，
     * 恒有 moved + merged = 合并前源标签的关联总数（不与 JDBC 驱动的 affected/found rows 设置耦合）。</p>
     *
     * @return { moved: 实际改挂的关联数, merged: 因冲突被合并掉的关联数, sourceId, targetId }
     */
    Map<String, Object> merge(Long sourceId, Long targetId);

    /**
     * 只改标签名（唯一性校验：重名 → 400；空 → 400；超 50 字按 biz_tag.name 长度截断）。
     *
     * <p>与 {@link #save(Tag)} 的区别：save 走的是「整体更新」，只传 {id, name} 时会把
     * slug 清空、sort 重置为 0；rename 只动 name，slug / sort 保持不变。</p>
     *
     * @return { id, name }
     */
    Map<String, Object> rename(Long id, String name);
}
