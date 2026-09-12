package com.culture.service;

import java.util.List;
import java.util.Map;

/**
 * 热门搜索词 + 搜索历史（增量）。
 *
 * <p>设计原则：统计与历史都是「锦上添花」，任何存储异常都必须被实现类吞掉，
 * 绝不能影响 /api/search 的主流程（搜索该出结果还得出结果，也不能 500）。</p>
 *
 * <ul>
 *   <li>热门词：MySQL 表 search_keyword_stat，每次搜索（关键词 >= 2 字）计数 +1；</li>
 *   <li>搜索历史：Redis List（key = {@code search:history:{userId}}），最新在前、去重、最多 20 条。</li>
 * </ul>
 */
public interface SearchStatService {

    /**
     * 记录一次搜索（关键词计数 +1）。
     * 关键词不足 2 个字、表不存在、SQL 异常都直接忽略，不抛出。
     */
    void recordKeyword(String keyword);

    /**
     * 热门搜索词 Top N。
     *
     * @param limit 期望条数（实现类会收敛到 1..20）
     * @return [{keyword, searchCount}]；无数据或查询失败返回空列表，绝不返回 null
     */
    List<Map<String, Object>> findHotKeywords(int limit);

    /**
     * 记录登录用户的搜索历史（LPUSH + LTRIM，去重后最多保留 20 条）。
     * Redis 不可用、参数不合法时直接忽略，不抛出。
     */
    void recordHistory(Long userId, String keyword);

    /**
     * 登录用户的最近搜索关键词（最新在前）。
     *
     * @param limit 期望条数（实现类会收敛到 1..20）
     * @return 关键词列表；无历史或 Redis 不可用返回空列表，绝不返回 null
     */
    List<String> findHistory(Long userId, int limit);
}
