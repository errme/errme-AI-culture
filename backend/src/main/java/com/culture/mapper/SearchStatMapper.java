package com.culture.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 热门搜索词统计 Mapper（表 search_keyword_stat，DDL 见 docs/sql/10_search.sql）。
 *
 * <p>只有两个动作：累加计数、取前 N。写入前关键词必须已由
 * {@link com.culture.util.SearchUtil#normalizeKeyword(String)} 统一 trim + 截断 50 字，
 * 否则可能超过 VARCHAR(100) 或产生「同一关键词多种写法」的重复统计。</p>
 */
@Mapper
public interface SearchStatMapper {

    /**
     * 关键词计数 +1：不存在则插入（search_count=1），已存在则累加并刷新最近搜索时间。
     *
     * <p>依赖唯一键 uk_keyword 才能触发 ON DUPLICATE KEY UPDATE；
     * 表还没建（未执行 10_search.sql）时这里会抛 SQL 异常，由 Service 捕获后静默忽略。</p>
     *
     * @return 受影响行数：插入返回 1，更新返回 2（MySQL 约定，调用方不需要关心）
     */
    @Insert("insert into search_keyword_stat(keyword, search_count, last_search_at, created_at) " +
            "values(#{keyword}, 1, now(), now()) " +
            "on duplicate key update search_count = search_count + 1, last_search_at = now()")
    int upsertKeyword(@Param("keyword") String keyword);

    /**
     * 热门搜索词 Top N：按搜索次数倒序，次数相同按最近搜索时间倒序。
     *
     * <p>返回每行一个 Map，键固定为 {@code keyword} / {@code searchCount}
     * （SQL 里已显式取别名 searchCount，避免依赖 map-underscore-to-camel-case）。</p>
     *
     * @param limit 条数，由 Service 收敛到 1..20
     */
    @Select("select keyword, search_count as searchCount from search_keyword_stat " +
            "order by search_count desc, last_search_at desc limit #{limit}")
    List<Map<String, Object>> findHotKeywords(@Param("limit") int limit);
}
