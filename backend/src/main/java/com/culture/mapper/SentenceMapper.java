package com.culture.mapper;

import com.culture.entity.Sentence;
import com.culture.query.SentenceQuery;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 句子 Mapper（新库 biz_sentence）。
 */
@Mapper
public interface SentenceMapper extends BaseMapper<Sentence>{

    List<Sentence> findAll();

    //最新4条
    @Select("select id, content, create_id createId, create_name createName, create_img createImg, " +
            "created_at createTime from biz_sentence where deleted=0 order by created_at desc limit 0,4")
    List<Sentence> queryAll();

    List<Sentence> queryData(SentenceQuery sentenceQuery);

    @Insert("insert into biz_sentence(content,create_id,create_name,created_at) " +
            "values (#{content},#{createId},#{createName},#{createTime})")
    void addSentence(Sentence sentence);

    @Update("update biz_sentence set content=#{content} where id=#{id}")
    void editSaveSentence(Sentence sentence);

    /** 逻辑删除 */
    @Update("update biz_sentence set deleted=1 where id=#{id}")
    void deleteSentence(Long id);

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /**
     * 恢复单条被逻辑删除的句子：只把 deleted 改回 0（content/create_* 等字段一律不动），
     * 返回实际影响行数；where 带 deleted=1，对正常记录调用返回 0。
     */
    @Update("update biz_sentence set deleted=0 where id=#{id} and deleted=1")
    int restoreById(@Param("id") Long id);

    /** 批量恢复被逻辑删除的句子；只影响 deleted=1 的行，返回实际影响行数（ids 由 Service 保证非空且 ≤ 500） */
    @Update("<script>" +
            "update biz_sentence set deleted=0 " +
            "where deleted=1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int restoreByIds(@Param("ids") List<Long> ids);

    // ===================== 全站搜索 =====================
    // 说明：kw 由 SearchUtil.prepareLikePattern 预转义（% / _ / ! 已带转义符），
    //      因此 SQL 里必须声明 `escape '!'`。
    //
    // 【增量】服务端组合筛选：句子侧只有 created_at 这一个可筛选字段，因此只支持时间范围，
    // 分类 / 标签对句子无意义（biz_sentence 既无 category_id 也无标签关联表）→ 在句子侧忽略，
    // 由 ApiSearchController 的注释向调用方说明；四个句子搜索语句共用下面的
    // SEARCH_TIME_FILTER_SQL，保证总数与列表口径一致。

    /**
     * 句子搜索的时间筛选片段（闭区间，与 {@link com.culture.util.SearchUtil#normalizeEndTime} 配套）。
     *
     * <p>startTime / endTime 为 null 时拼接结果为空串，SQL 与改造前逐字一致。
     * SQL 里的小于等于必须写成实体形式，否则用 &lt;script&gt; 包起来的这段动态 SQL
     * 在 MyBatis 解析阶段就会报 XML 语法错误。</p>
     */
    String SEARCH_TIME_FILTER_SQL =
            "<if test='startTime != null'> and created_at &gt;= #{startTime}</if>" +
                    "<if test='endTime != null'> and created_at &lt;= #{endTime}</if>";

    //搜索命中的句子总数（content / create_name 模糊匹配，含逻辑删除过滤）
    //【增量】末尾追加可选时间范围筛选（分类/标签在句子侧忽略，见上方说明）
    @Select("<script>select count(*) from biz_sentence where deleted=0 and (" +
            "content like #{kw} escape '!' or create_name like #{kw} escape '!')" +
            SEARCH_TIME_FILTER_SQL +
            "</script>")
    Long querySearchTotal(@Param("kw") String kw,
                          @Param("startTime") String startTime,
                          @Param("endTime") String endTime);

    //搜索命中的句子分页数据：正文命中优先，其次按时间倒序（id 兜底，保证分页稳定）
    //【增量】筛选条件与 querySearchTotal 共用 SEARCH_TIME_FILTER_SQL，保证总数与列表口径一致
    @Select("<script>select id, content, create_id createId, create_name createName, create_img createImg, " +
            "created_at createTime from biz_sentence where deleted=0 and (" +
            "content like #{kw} escape '!' or create_name like #{kw} escape '!')" +
            SEARCH_TIME_FILTER_SQL +
            " order by (content like #{kw} escape '!') desc, created_at desc, id desc " +
            "limit #{offset}, #{pageSize}</script>")
    List<Sentence> querySearchData(@Param("kw") String kw,
                                   @Param("offset") Integer offset,
                                   @Param("pageSize") Integer pageSize,
                                   @Param("startTime") String startTime,
                                   @Param("endTime") String endTime);

    // ===================== 全站搜索：ngram 全文检索（增量） =====================
    // FULLTEXT 索引建在 biz_sentence(content) 上（见 docs/sql/10_search.sql 的 ft_sentence_search）。
    // · kw     = SearchUtil.toBooleanPhrase(keyword)，形如 "文化"（引号短语，等价于子串匹配）；
    // · likeKw = SearchUtil.toLikePattern(keyword)，用于 create_name（作者名）匹配与排序。
    //
    // 注意：旧 LIKE 查询是 (content like ? or create_name like ?)，作者名不在全文索引里，
    // 所以全文条件必须 or 上 create_name 的 LIKE，否则「搜作者名」会从有结果变成 0 条；
    // 代价是这种 OR 可能让优化器放弃 FULLTEXT 索引（数据量小，正确性优先）。
    // 索引不存在时 MySQL 报 ERROR 1191，由 Service 捕获后降级回上面的 LIKE 查询。

    /** 全文检索命中的句子总数（content 走全文，create_name 仍走 LIKE，与旧语义一致；时间筛选与 LIKE 路径同一片段） */
    @Select("<script>select count(*) from biz_sentence where deleted=0 and (" +
            "MATCH(content) AGAINST(#{kw} IN BOOLEAN MODE) or create_name like #{likeKw} escape '!')" +
            SEARCH_TIME_FILTER_SQL +
            "</script>")
    Long querySearchTotalByFullText(@Param("kw") String kw,
                                    @Param("likeKw") String likeKw,
                                    @Param("startTime") String startTime,
                                    @Param("endTime") String endTime);

    /** 全文检索命中的句子分页数据（字段/过滤/排序与 querySearchData 一致，前端无感；时间筛选与 LIKE 路径同一片段） */
    @Select("<script>select id, content, create_id createId, create_name createName, create_img createImg, " +
            "created_at createTime from biz_sentence where deleted=0 and (" +
            "MATCH(content) AGAINST(#{kw} IN BOOLEAN MODE) or create_name like #{likeKw} escape '!')" +
            SEARCH_TIME_FILTER_SQL +
            " order by (content like #{likeKw} escape '!') desc, created_at desc, id desc " +
            "limit #{offset}, #{pageSize}</script>")
    List<Sentence> querySearchDataByFullText(@Param("kw") String kw,
                                             @Param("likeKw") String likeKw,
                                             @Param("offset") Integer offset,
                                             @Param("pageSize") Integer pageSize,
                                             @Param("startTime") String startTime,
                                             @Param("endTime") String endTime);
}
