package com.culture.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 回收站 Mapper（全新文件，纯注解 SQL，不依赖 XML）。
 *
 * <p>职责只有两类，都是既有 7 类逻辑删除实体（deleted=1）的「已删除视图」：</p>
 * <ol>
 *   <li><b>只读</b>：{@link #countAll()} 一次查出 7 张表的删除条数（单条 SQL 的标量子查询，
 *       不是 7 次往返，避免 N+1）；{@code countXxx/listXxx} 按类型分页列出 deleted=1 的行。</li>
 *   <li><b>物理删除</b>：{@code purgeXxx} 一律带 <code>deleted = 1</code> 条件，
 *       只删「已经逻辑删除」的行，返回实际删除行数；关联表清理见各方法注释。</li>
 * </ol>
 *
 * <p>为什么单独建一个 Mapper 而不是改各实体 Mapper：回收站是横跨 7 张表的聚合视图，
 * 放在一个文件里便于整体核对；既有 Culture/Tag/User Mapper 一个字都不用动，
 * 对既有接口零影响（向后兼容）。</p>
 *
 * <p>富文本列的处理：文化 content、公告 content、评论 content 都是大字段，
 * 列表 SQL 一律用 <code>LEFT(col, 120)</code> 截断，绝不把 longtext 全文带进响应。</p>
 *
 * <p>时间列差异（本库实测 DDL）：</p>
 * <ul>
 *   <li>biz_culture / biz_category / biz_announcement / biz_sentence / sys_user 有 updated_at，
 *       deletedTime 取 updated_at（逻辑删除时 ON UPDATE CURRENT_TIMESTAMP 会自动刷新）；</li>
 *   <li>biz_tag / biz_comment <b>没有 updated_at</b>，deletedTime 只能退化为 created_at
 *       （Service 会在该行的 extra 语义里保留区分，见 RecycleServiceImpl 注释）。</li>
 * </ul>
 */
@Mapper
public interface RecycleMapper {

    // =====================================================================
    // 1. 回收站角标：7 张表 deleted=1 计数，一条 SQL 查完
    // =====================================================================

    /**
     * 一次查回 7 类实体的回收站条数。列别名即响应字段名（user 是 MySQL 非保留字，
     * 但为稳妥仍加反引号）。每个标量子查询都是 count(*)，不会返回 null。
     */
    @Select("select " +
            "(select count(*) from biz_culture      where deleted = 1) as culture, " +
            "(select count(*) from biz_category     where deleted = 1) as category, " +
            "(select count(*) from biz_tag          where deleted = 1) as tag, " +
            "(select count(*) from biz_announcement where deleted = 1) as announcement, " +
            "(select count(*) from biz_sentence     where deleted = 1) as sentence, " +
            "(select count(*) from biz_comment      where deleted = 1) as comment, " +
            "(select count(*) from sys_user         where deleted = 1) as `user`")
    Map<String, Object> countAll();

    // =====================================================================
    // 2. 回收站分页：count（keyword 已由 Service 用 SearchUtil 转义好）
    //    说明：kw 为 null 时不拼 like 条件；SQL 里必须写 escape '!'，
    //          与 SearchUtil.LIKE_ESCAPE_CHAR 配套（否则转义符会被当普通字符）。
    // =====================================================================

    @Select("<script>select count(*) from biz_culture where deleted = 1" +
            "<if test=\"kw != null and kw != ''\"> and name like #{kw} escape '!'</if></script>")
    Long countCulture(@Param("kw") String kw);

    @Select("<script>select count(*) from biz_category where deleted = 1" +
            "<if test=\"kw != null and kw != ''\"> and name like #{kw} escape '!'</if></script>")
    Long countCategory(@Param("kw") String kw);

    @Select("<script>select count(*) from biz_tag where deleted = 1" +
            "<if test=\"kw != null and kw != ''\"> and name like #{kw} escape '!'</if></script>")
    Long countTag(@Param("kw") String kw);

    @Select("<script>select count(*) from biz_announcement where deleted = 1" +
            "<if test=\"kw != null and kw != ''\"> and title like #{kw} escape '!'</if></script>")
    Long countAnnouncement(@Param("kw") String kw);

    @Select("<script>select count(*) from biz_sentence where deleted = 1" +
            "<if test=\"kw != null and kw != ''\"> and content like #{kw} escape '!'</if></script>")
    Long countSentence(@Param("kw") String kw);

    @Select("<script>select count(*) from biz_comment where deleted = 1" +
            "<if test=\"kw != null and kw != ''\"> and content like #{kw} escape '!'</if></script>")
    Long countComment(@Param("kw") String kw);

    @Select("<script>select count(*) from sys_user where deleted = 1" +
            "<if test=\"kw != null and kw != ''\"> and (username like #{kw} escape '!' " +
            "or email like #{kw} escape '!')</if></script>")
    Long countUser(@Param("kw") String kw);

    // =====================================================================
    // 3. 回收站分页：数据行（统一按 id 倒序，保证分页稳定）
    //    每行返回：id / title / summary(可空) / extra(可空) / createTime / deletedTime
    //    Service 会把 Map 归一化成固定 6 个键（MyBatis 对 null 列默认不写进 Map）。
    // =====================================================================

    /** 文化：title=名称，summary=LEFT(正文,120)，extra=分类名/作者名 */
    @Select("<script>select c.id as id, c.name as title, LEFT(c.content, 120) as summary, " +
            "concat_ws(' / ', cat.name, u.username) as extra, " +
            "c.created_at as createTime, c.updated_at as deletedTime " +
            "from biz_culture c " +
            "left join biz_category cat on cat.id = c.category_id " +
            "left join sys_user u on u.id = c.creator_id " +
            "where c.deleted = 1" +
            "<if test=\"kw != null and kw != ''\"> and c.name like #{kw} escape '!'</if> " +
            "order by c.id desc limit #{offset}, #{pageSize}</script>")
    List<Map<String, Object>> listCulture(@Param("kw") String kw,
                                         @Param("offset") int offset,
                                         @Param("pageSize") int pageSize);

    /** 分类：title=名称 */
    @Select("<script>select c.id as id, c.name as title, " +
            "c.created_at as createTime, c.updated_at as deletedTime " +
            "from biz_category c where c.deleted = 1" +
            "<if test=\"kw != null and kw != ''\"> and c.name like #{kw} escape '!'</if> " +
            "order by c.id desc limit #{offset}, #{pageSize}</script>")
    List<Map<String, Object>> listCategory(@Param("kw") String kw,
                                          @Param("offset") int offset,
                                          @Param("pageSize") int pageSize);

    /**
     * 标签：title=名称，extra=slug。
     * biz_tag 没有 updated_at，deletedTime 只能退化为 created_at（见类注释）。
     */
    @Select("<script>select t.id as id, t.name as title, t.slug as extra, " +
            "t.created_at as createTime, t.created_at as deletedTime " +
            "from biz_tag t where t.deleted = 1" +
            "<if test=\"kw != null and kw != ''\"> and t.name like #{kw} escape '!'</if> " +
            "order by t.id desc limit #{offset}, #{pageSize}</script>")
    List<Map<String, Object>> listTag(@Param("kw") String kw,
                                      @Param("offset") int offset,
                                      @Param("pageSize") int pageSize);

    /** 公告：title=公告内容(LEFT 120)，summary=正文 content(LEFT 120，通常与 title 同源) */
    @Select("<script>select a.id as id, LEFT(a.title, 120) as title, LEFT(a.content, 120) as summary, " +
            "a.created_at as createTime, a.updated_at as deletedTime " +
            "from biz_announcement a where a.deleted = 1" +
            "<if test=\"kw != null and kw != ''\"> and a.title like #{kw} escape '!'</if> " +
            "order by a.id desc limit #{offset}, #{pageSize}</script>")
    List<Map<String, Object>> listAnnouncement(@Param("kw") String kw,
                                               @Param("offset") int offset,
                                               @Param("pageSize") int pageSize);

    /** 句子：title=内容(LEFT 120)，extra=作者名 create_name */
    @Select("<script>select s.id as id, LEFT(s.content, 120) as title, s.create_name as extra, " +
            "s.created_at as createTime, s.updated_at as deletedTime " +
            "from biz_sentence s where s.deleted = 1" +
            "<if test=\"kw != null and kw != ''\"> and s.content like #{kw} escape '!'</if> " +
            "order by s.id desc limit #{offset}, #{pageSize}</script>")
    List<Map<String, Object>> listSentence(@Param("kw") String kw,
                                           @Param("offset") int offset,
                                           @Param("pageSize") int pageSize);

    /**
     * 评论：title=内容(LEFT 120，HTML 已清洗)，extra=昵称/所属文化名。
     * biz_comment 没有 updated_at，deletedTime 退化为 created_at（见类注释）。
     */
    @Select("<script>select c.id as id, LEFT(c.content, 120) as title, " +
            "concat_ws(' / ', c.nickname, cu.name) as extra, " +
            "c.created_at as createTime, c.created_at as deletedTime " +
            "from biz_comment c " +
            "left join biz_culture cu on cu.id = c.culture_id " +
            "where c.deleted = 1" +
            "<if test=\"kw != null and kw != ''\"> and c.content like #{kw} escape '!'</if> " +
            "order by c.id desc limit #{offset}, #{pageSize}</script>")
    List<Map<String, Object>> listComment(@Param("kw") String kw,
                                          @Param("offset") int offset,
                                          @Param("pageSize") int pageSize);

    /** 用户：title=用户名，extra=邮箱（旧库删除的账号没有昵称也便于辨认） */
    @Select("<script>select u.id as id, u.username as title, u.email as extra, " +
            "u.created_at as createTime, u.updated_at as deletedTime " +
            "from sys_user u where u.deleted = 1" +
            "<if test=\"kw != null and kw != ''\"> and (u.username like #{kw} escape '!' " +
            "or u.email like #{kw} escape '!')</if> " +
            "order by u.id desc limit #{offset}, #{pageSize}</script>")
    List<Map<String, Object>> listUser(@Param("kw") String kw,
                                       @Param("offset") int offset,
                                       @Param("pageSize") int pageSize);

    // =====================================================================
    // 4. 物理删除（彻底删除）
    //    硬性约束：where 必须带 deleted = 1，只能删「已逻辑删除」的行；
    //              返回实际删除行数（不存在的 id / 未删除的 id 自动忽略）。
    // =====================================================================

    /** 物理删除文化：delete from biz_culture where deleted = 1 and id in (...) */
    @Delete("<script>delete from biz_culture where deleted = 1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int purgeCulture(@Param("ids") List<Long> ids);

    /** 物理删除分类（biz_culture.category_id 外键是 ON DELETE SET NULL，数据库自动置空） */
    @Delete("<script>delete from biz_category where deleted = 1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int purgeCategory(@Param("ids") List<Long> ids);

    /** 物理删除标签 */
    @Delete("<script>delete from biz_tag where deleted = 1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int purgeTag(@Param("ids") List<Long> ids);

    /** 物理删除公告 */
    @Delete("<script>delete from biz_announcement where deleted = 1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int purgeAnnouncement(@Param("ids") List<Long> ids);

    /** 物理删除句子（biz_sentence.create_id 外键 ON DELETE SET NULL） */
    @Delete("<script>delete from biz_sentence where deleted = 1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int purgeSentence(@Param("ids") List<Long> ids);

    /** 物理删除评论 */
    @Delete("<script>delete from biz_comment where deleted = 1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int purgeComment(@Param("ids") List<Long> ids);

    /** 物理删除用户（sys_user_role / biz_like 数据库外键为 ON DELETE CASCADE，其余 SET NULL） */
    @Delete("<script>delete from sys_user where deleted = 1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int purgeUser(@Param("ids") List<Long> ids);

    // =====================================================================
    // 5. 物理删除的级联清理（关联表本身没有逻辑删除列，直接物理删）
    //    子查询里再带一次 deleted = 1：只清理「确实会被删掉」的主表行关联，
    //    避免把请求里那些 deleted=0（不会被删）的 id 的关联误删。
    // =====================================================================

    /** 文化彻底删除时，清理 biz_culture_tag 里这些 culture_id 的关联（该表无外键，必须手动清） */
    @Delete("<script>delete from biz_culture_tag where culture_id in (" +
            "select id from biz_culture where deleted = 1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            ")</script>")
    int purgeCultureTagByCultureIds(@Param("ids") List<Long> ids);

    /** 标签彻底删除时，清理 biz_culture_tag 里这些 tag_id 的关联 */
    @Delete("<script>delete from biz_culture_tag where tag_id in (" +
            "select id from biz_tag where deleted = 1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            ")</script>")
    int purgeCultureTagByTagIds(@Param("ids") List<Long> ids);

    /** 用户彻底删除时，清理这些用户的 sys_user_role 行（外键也会级联，这里显式清便于统计） */
    @Delete("<script>delete from sys_user_role where user_id in (" +
            "select id from sys_user where deleted = 1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            ")</script>")
    int purgeUserRoleByUserIds(@Param("ids") List<Long> ids);

    // =====================================================================
    // 6. 用户彻底删除的「最后一个管理员」保护（只读查询）
    // =====================================================================

    /**
     * 当前持有「管理员」角色（sys_role.name='管理员'）的用户数。
     * 与 ApiAdminController#isAdmin 的口径一致：RoleMapper.listRoleByUserId 不过滤
     * sys_role.deleted，因此这里也不过滤，保证「能不能进后台」与「算不算管理员」判断一致。
     */
    @Select("select count(distinct ur.user_id) from sys_user_role ur " +
            "join sys_role r on r.id = ur.role_id where r.name = '管理员'")
    long countAdminRoleUsers();

    /**
     * 这批 id 里「既是管理员、又确实会被物理删除（sys_user.deleted=1）」的用户数。
     * 只统计会被真正删掉的那些：请求里 deleted=0 的管理员不会被本接口删除，
     * 不应计入「删掉后还剩几个管理员」的减法。
     */
    @Select("<script>select count(distinct ur.user_id) from sys_user_role ur " +
            "join sys_role r on r.id = ur.role_id " +
            "join sys_user u on u.id = ur.user_id and u.deleted = 1 " +
            "where r.name = '管理员' and ur.user_id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int countPurgeableAdminUsers(@Param("ids") List<Long> ids);
}
