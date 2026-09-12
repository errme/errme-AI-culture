package com.culture.service;

import com.culture.entity.Sentence;
import com.culture.query.SentenceQuery;
import com.culture.util.PageList;


import java.util.List;

public interface SentenceService {

    List<Sentence> findAll();

    List<Sentence> queryAll();

    PageList listpage(SentenceQuery sentenceQuery);

    void addSentence(Sentence sentence);

    void editSaveSentence(Sentence sentence);

    void deleteSentence(Long id);

    //全站搜索：命中的句子总数（keyword 传原始值，Impl 内部做 trim/截断/LIKE 转义）
    Long querySearchTotal(String keyword);

    //全站搜索：分页取句子数据（正文命中优先，其次创建时间倒序）
    List<Sentence> querySearchData(String keyword, Integer offset, Integer pageSize);

    /**
     * 【增量】全站搜索（句子）带时间范围筛选的命中总数。
     *
     * <p>句子侧只有 created_at 可筛（biz_sentence 没有分类、也没有标签关联表），
     * 所以分类 / 标签筛选在句子侧<b>忽略</b>，只按时间范围收窄。
     * 两个时间参数都为 null 时与 {@link #querySearchTotal(String)} 等价。</p>
     *
     * @param startTime 创建时间闭区间起点（含），null = 不限
     * @param endTime   创建时间闭区间终点（含），null = 不限
     */
    Long querySearchTotal(String keyword, String startTime, String endTime);

    /**
     * 【增量】全站搜索（句子）带时间范围筛选的分页数据。
     *
     * <p>筛选口径与 {@link #querySearchTotal(String, String, String)} 共用同一段 SQL 片段，
     * 保证总数与列表一致；时间为 null 时与
     * {@link #querySearchData(String, Integer, Integer)} 等价。</p>
     */
    List<Sentence> querySearchData(String keyword, Integer offset, Integer pageSize,
                                   String startTime, String endTime);

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /**
     * 恢复单条被逻辑删除的句子（deleted 1 → 0，只改这一列）。
     * 权限校验与删除一致，由控制层（/api/admin/sentence/restore，要求管理员）负责。
     *
     * @param id 句子 id；为 null 时抛 BusinessException（控制层转 400）
     * @return 实际影响行数；0 表示该 id 不存在或本来就没被删除
     */
    int restore(Long id);

    /**
     * 批量恢复被逻辑删除的句子。
     *
     * @param ids 句子 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException（控制层转 400）
     * @return 实际影响行数
     */
    int restoreBatch(List<Long> ids);
}
