package com.culture.mapper;

import com.culture.entity.Comment;
import com.culture.query.CommentQuery;
import org.apache.ibatis.annotations.*;

import java.util.Date;
import java.util.List;

/**
 * 评论 Mapper（新库 biz_comment，注解 SQL 风格）。
 *
 * <p>列表类 SQL 统一只取 deleted = 0；后台分页 join biz_culture 取文化名（cultureName）。</p>
 */
@Mapper
public interface CommentMapper {

    /** 新增评论（返回自增主键） */
    @Insert("insert into biz_comment(culture_id, parent_id, user_id, nickname, email, content, status, ip, user_agent, created_at) " +
            "values(#{cultureId}, #{parentId}, #{userId}, #{nickname}, #{email}, #{content}, #{status}, #{ip}, #{userAgent}, now())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Comment comment);

    /** 某文化下已通过的评论（按 id 升序，供前台组装两级树） */
    @Select("select id, culture_id cultureId, parent_id parentId, user_id userId, nickname, content, status, " +
            "created_at createTime from biz_comment " +
            "where culture_id = #{cultureId} and status = 1 and deleted = 0 order by id asc")
    List<Comment> findApprovedByCulture(Long cultureId);

    /** 某文化下已通过的评论数 */
    @Select("select count(*) from biz_comment where culture_id = #{cultureId} and status = 1 and deleted = 0")
    Long countApprovedByCulture(Long cultureId);

    /** 后台分页：总数 */
    @Select("<script>" +
            "select count(*) from biz_comment c where c.deleted = 0 " +
            "<if test='status != null and status != -1'> and c.status = #{status} </if>" +
            "<if test='cultureId != null'> and c.culture_id = #{cultureId} </if>" +
            "<if test=\"keyword != null and keyword != ''\">" +
            " and (c.content like concat('%', #{keyword}, '%') or c.nickname like concat('%', #{keyword}, '%'))" +
            "</if>" +
            "</script>")
    Long queryTotal(CommentQuery query);

    /** 后台分页：数据（join biz_culture 取文化名，按 id 倒序） */
    @Select("<script>" +
            "select c.id, c.culture_id cultureId, c.parent_id parentId, c.user_id userId, c.nickname, c.email, " +
            "c.content, c.status, c.ip, c.user_agent userAgent, c.created_at createTime, " +
            "c.audited_at auditedAt, c.audited_by auditedBy, u.name cultureName " +
            "from biz_comment c left join biz_culture u on u.id = c.culture_id " +
            "where c.deleted = 0 " +
            "<if test='status != null and status != -1'> and c.status = #{status} </if>" +
            "<if test='cultureId != null'> and c.culture_id = #{cultureId} </if>" +
            "<if test=\"keyword != null and keyword != ''\">" +
            " and (c.content like concat('%', #{keyword}, '%') or c.nickname like concat('%', #{keyword}, '%'))" +
            "</if>" +
            " order by c.id desc limit #{offset}, #{pageSize}" +
            "</script>")
    List<Comment> queryData(CommentQuery query);

    /** 审核（status 1 通过 / 2 拒绝），同时记录审核人与审核时间 */
    @Update("update biz_comment set status = #{status}, audited_by = #{auditedBy}, audited_at = #{auditedAt} " +
            "where id = #{id} and deleted = 0")
    int updateStatus(@Param("id") Long id,
                     @Param("status") int status,
                     @Param("auditedBy") Long auditedBy,
                     @Param("auditedAt") Date auditedAt);

    /** 逻辑删除 */
    @Update("update biz_comment set deleted = 1 where id = #{id}")
    int logicalDelete(Long id);

    /** 按状态统计（待审数量用） */
    @Select("select count(*) from biz_comment where status = #{status} and deleted = 0")
    Long countByStatus(Integer status);

    /** 按主键查询（审核/管理用） */
    @Select("select id, culture_id cultureId, parent_id parentId, user_id userId, nickname, email, content, " +
            "status, ip, user_agent userAgent, created_at createTime, audited_at auditedAt, audited_by auditedBy " +
            "from biz_comment where id = #{id} and deleted = 0")
    Comment findById(Long id);

    // ===================== 后台批量操作（增量追加） =====================

    /**
     * 批量审核（status 1 通过 / 2 拒绝），同时记录审核人与审核时间。
     * 只影响未删除的评论；返回实际更新条数。ids 由 Service 保证非空且 ≤ 500。
     */
    @Update("<script>" +
            "update biz_comment set status = #{status}, audited_by = #{auditedBy}, audited_at = #{auditedAt} " +
            "where deleted = 0 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int batchUpdateStatus(@Param("ids") List<Long> ids,
                          @Param("status") int status,
                          @Param("auditedBy") Long auditedBy,
                          @Param("auditedAt") Date auditedAt);

    /** 批量逻辑删除；返回实际更新条数 */
    @Update("<script>" +
            "update biz_comment set deleted = 1 " +
            "where deleted = 0 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int batchLogicalDelete(@Param("ids") List<Long> ids);

    // ===================== 重新过词表（增量追加） =====================

    /**
     * 按 id 取「待审核（status=0）且未删除」的评论，供 /api/admin/comment/recheck 重新过敏感词表。
     * 只取匹配需要的列（content 用于判定，id 用于回写）；ids 由 Service 保证非空且 ≤ 500。
     */
    @Select("<script>" +
            "select id, culture_id cultureId, content, status, nickname, created_at createTime " +
            "from biz_comment " +
            "where deleted = 0 and status = 0 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<Comment> findPendingByIds(@Param("ids") List<Long> ids);

    // ===================== 删除可撤销：恢复（增量追加） =====================
    // 恢复只改 deleted，status / audited_by / audited_at 等审核信息保持原样：
    // 比如删除前是「已通过(1)」的评论，恢复后仍然是已通过，不需要重新审核。

    /**
     * 恢复单条被逻辑删除的评论：只把 deleted 改回 0，返回实际影响行数；
     * where 带 deleted=1，对正常记录调用返回 0。
     */
    @Update("update biz_comment set deleted = 0 where id = #{id} and deleted = 1")
    int restoreById(@Param("id") Long id);

    /** 批量恢复被逻辑删除的评论；只影响 deleted=1 的行，返回实际影响行数（ids 由 Service 保证非空且 ≤ 500） */
    @Update("<script>" +
            "update biz_comment set deleted = 0 " +
            "where deleted = 1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int restoreByIds(@Param("ids") List<Long> ids);
}
