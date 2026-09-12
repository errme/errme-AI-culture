package com.culture.mapper;

import com.culture.entity.Culture;
import com.culture.entity.Tag;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 标签 Mapper（新库 biz_tag / biz_culture_tag，注解 SQL 风格）。
 * 列名 created_at 由 map-underscore-to-camel-case 映射为 createTime，此处仍显式起别名，语义更清晰。
 */
@Mapper
public interface TagMapper {

    /** 全部未删除标签（按 sort,id 升序） */
    @Select("select id, name, slug, sort, created_at createTime, deleted from biz_tag " +
            "where deleted = 0 order by sort, id")
    List<Tag> queryAll();

    /** 按主键查询（仅未删除） */
    @Select("select id, name, slug, sort, created_at createTime, deleted from biz_tag " +
            "where id = #{id} and deleted = 0")
    Tag findById(Long id);

    /**
     * 按名称查询（<b>不过滤 deleted</b>）。
     * 表上 name 是唯一键，逻辑删除的标签仍占用名称，这里返回它便于「复活」而不是插入冲突。
     */
    @Select("select id, name, slug, sort, created_at createTime, deleted from biz_tag " +
            "where name = #{name} limit 1")
    Tag findByName(String name);

    /** 新增 */
    @Insert("insert into biz_tag(name, slug, sort, created_at) values(#{name}, #{slug}, #{sort}, now())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Tag tag);

    /** 更新（名称/标识/排序） */
    @Update("update biz_tag set name = #{name}, slug = #{slug}, sort = #{sort} where id = #{id}")
    int update(Tag tag);

    /** 逻辑删除 */
    @Update("update biz_tag set deleted = 1 where id = #{id}")
    int logicalDelete(Long id);

    /** 复活被逻辑删除的标签（同名重复新增时复用旧行） */
    @Update("update biz_tag set deleted = 0, slug = #{slug}, sort = #{sort} where id = #{id}")
    int revive(Tag tag);

    /** 某个文化挂的全部标签 */
    @Select("select t.id, t.name, t.slug, t.sort, t.created_at createTime " +
            "from biz_tag t join biz_culture_tag ct on ct.tag_id = t.id " +
            "where ct.culture_id = #{cultureId} and t.deleted = 0 order by t.sort, t.id")
    List<Tag> findTagsByCultureId(Long cultureId);

    /** 解除某个文化的全部标签关联 */
    @Delete("delete from biz_culture_tag where culture_id = #{cultureId}")
    int deleteCultureTags(Long cultureId);

    /** 绑定（重复绑定忽略，依赖唯一键 uk_culture_tag） */
    @Insert("insert ignore into biz_culture_tag(culture_id, tag_id, created_at) " +
            "values(#{cultureId}, #{tagId}, now())")
    int bindCulture(@Param("cultureId") Long cultureId, @Param("tagId") Long tagId);

    /**
     * 批量查询多个文化的标签（列表页一次取回，避免 N+1）。
     * 结果里每行的 cultureId 表示该标签属于哪个文化，由 Tag.cultureId 承载（不输出到 JSON）。
     */
    @Select("<script>" +
            "select ct.culture_id cultureId, t.id, t.name, t.slug, t.sort, t.created_at createTime " +
            "from biz_culture_tag ct join biz_tag t on t.id = ct.tag_id " +
            "where ct.culture_id in " +
            "<foreach collection='ids' item='cid' open='(' separator=',' close=')'>#{cid}</foreach> " +
            "and t.deleted = 0 order by t.sort, t.id" +
            "</script>")
    List<Tag> findTagsByCultureIds(@Param("ids") List<Long> cultureIds);

    /**
     * 各标签的内容数（只统计未删除的文化），用于列表回填 Tag.cultureCount。
     * <b>刻意不过滤 status</b>：本方法被前台 /api/tag/list 与后台 /api/admin/tag/list 共用
     * （都走 TagService.queryAll），后台口径必须保持原样，因此这里不加 status=1。
     */
    @Select("select ct.tag_id id, count(*) cultureCount from biz_culture_tag ct " +
            "join biz_culture c on c.id = ct.culture_id and c.deleted = 0 " +
            "group by ct.tag_id")
    List<Tag> countCulturesGroupByTag();

    /**
     * 某标签下文化总数（分页用，前台 /api/tag/cultures）。
     * <b>前台可见性</b>：只统计已发布（status=1）的内容，草稿 / 已下架 / 定时未到点不计入。
     * 本方法只被 TagServiceImpl.culturesOfTag 调用，而后者只被 ApiTagController（前台匿名）调用，
     * 后台标签接口只使用 {@link #queryAll()}，因此加 status=1 不影响后台行为。
     */
    @Select("select count(*) from biz_culture c join biz_culture_tag ct on ct.culture_id = c.id " +
            "where ct.tag_id = #{tagId} and c.deleted = 0 and c.status = 1")
    Long countCulturesByTagId(@Param("tagId") Long tagId);

    /**
     * 某标签下的文化分页（按 id 倒序，前台 /api/tag/cultures）。
     * 列表瘦身：只取卡片需要的列 + LEFT(content,300) 摘要，不取 longtext 正文（与 /api/culture/list 一致）。
     * <b>前台可见性</b>：加上 status = 1，与上面的 count 口径一致（否则会出现「总数 3、列表 1 条」）。
     */
    @Select("select c.id, c.name, c.address, c.description, LEFT(c.content, 300) infoSummary, " +
            "c.cover_url, c.category_id, c.view_count, " +
            "c.like_count, c.created_at " +
            "from biz_culture c join biz_culture_tag ct on ct.culture_id = c.id " +
            "where ct.tag_id = #{tagId} and c.deleted = 0 and c.status = 1 " +
            "order by c.id desc limit #{offset}, #{pageSize}")
    @Results(id = "tagCultureMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "cultureName", column = "name"),
            @Result(property = "address", column = "address"),
            @Result(property = "desc", column = "description"),
            @Result(property = "infoSummary", column = "infoSummary"),
            @Result(property = "fmUrl", column = "cover_url"),
            @Result(property = "categoryId", column = "category_id"),
            @Result(property = "view", column = "view_count"),
            @Result(property = "likeCount", column = "like_count"),
            @Result(property = "createTime", column = "created_at")
    })
    List<Culture> queryCulturesByTagId(@Param("tagId") Long tagId,
                                       @Param("offset") int offset,
                                       @Param("pageSize") int pageSize);

    // ===================== 后台批量操作（增量追加） =====================

    /** 批量解除多个标签的文化关联（关联表无逻辑删除，直接物理删除） */
    @Delete("<script>" +
            "delete from biz_culture_tag where tag_id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int batchDeleteCultureTags(@Param("ids") List<Long> ids);

    /** 批量逻辑删除标签；返回实际更新条数 */
    @Update("<script>" +
            "update biz_tag set deleted = 1 " +
            "where deleted = 0 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int batchLogicalDelete(@Param("ids") List<Long> ids);

    // ===================== 标签合并 / 改名（增量追加） =====================

    /**
     * 把 sourceId 的文化关联整体改挂到 targetId（标签合并第一步）。
     *
     * <p><b>为什么必须 UPDATE IGNORE</b>：biz_culture_tag 上有唯一键 uk_culture_tag(culture_id, tag_id)，
     * 若某个文化同时挂了源标签和目标标签，直接 update 会撞 1062 让整个合并失败；
     * 带 IGNORE 时 MySQL 会「跳过冲突行、继续改挂其余行」（冲突行原样保留在源标签下，
     * 由 {@link #countCultureTagsByTagId(Long)} 数出来后再
     * {@link #deleteCultureTagsByTagId(Long)} 物理删除）。</p>
     *
     * <p><b>返回值不要用来统计 moved</b>：项目 JDBC URL 未设置 useAffectedRows，
     * Connector/J 默认带 CLIENT_FOUND_ROWS，executeUpdate 返回的是「匹配行数」而不是「实际改挂行数」。
     * 合并结果统一用「改挂前/改挂后的关联计数差」计算，与驱动设置无关。</p>
     */
    @Update("update ignore biz_culture_tag set tag_id = #{targetId} where tag_id = #{sourceId}")
    int moveCultureTags(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId);

    /** 某标签当前的文化关联数（合并前后各查一次，用来算 moved / merged） */
    @Select("select count(*) from biz_culture_tag where tag_id = #{tagId}")
    int countCultureTagsByTagId(@Param("tagId") Long tagId);

    /** 物理删除某个标签的全部文化关联（合并时清理「改挂被 IGNORE 跳过」的冲突残留） */
    @Delete("delete from biz_culture_tag where tag_id = #{tagId}")
    int deleteCultureTagsByTagId(@Param("tagId") Long tagId);

    /**
     * 只改标签名（/api/admin/tag/rename 用）：
     * 与 {@link #update(Tag)} 不同，它<b>不动 slug / sort</b>，避免「只想改名却把标识和排序清掉」。
     */
    @Update("update biz_tag set name = #{name} where id = #{id} and deleted = 0")
    int updateName(@Param("id") Long id, @Param("name") String name);

    // ===================== 删除可撤销：恢复（增量追加） =====================
    // 【重要说明】标签删除时 biz_culture_tag 里的文化关联是被「物理删除」的，
    // 无法从数据库还原，因此恢复标签只恢复 biz_tag.deleted=0，<b>不要求也不尝试恢复原关联</b>；
    // 恢复后标签在标签列表中重新出现，但内容数为 0，需要管理员重新在文化编辑页绑定。
    // （如果产品希望「恢复标签 = 恢复关联」，需要在删除时改成给关联表加逻辑删除列，
    //   这属于表结构变更，本次不做。）

    /**
     * 恢复单条被逻辑删除的标签：只把 deleted 改回 0（name/slug/sort 一律不动），
     * 返回实际影响行数；where 带 deleted=1，对正常记录调用返回 0。
     */
    @Update("update biz_tag set deleted = 0 where id = #{id} and deleted = 1")
    int restoreById(@Param("id") Long id);

    /** 批量恢复被逻辑删除的标签；只影响 deleted=1 的行，返回实际影响行数（ids 由 Service 保证非空且 ≤ 500） */
    @Update("<script>" +
            "update biz_tag set deleted = 0 " +
            "where deleted = 1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int restoreByIds(@Param("ids") List<Long> ids);
}
