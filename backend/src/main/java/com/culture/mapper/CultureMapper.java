package com.culture.mapper;

import com.culture.entity.Culture;
import com.culture.entity.Like;
import com.culture.query.CultureQuery;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 文化 Mapper（新库 biz_culture / biz_like）。
 * 注解 SQL 统一把新库列映射回旧实体属性名，保证 service/template 零改动。
 *
 * <p><b>前台可见性约定（status：0=草稿 / 下架，1=已发布，2=定时待发布）</b>：
 * 只被前台（/api/**，含搜索、标签页、个人中心收藏、首页区块、SEO）使用的查询，
 * SQL 里一律自带 {@code status = 1}；而<b>前后台共用</b>的查询
 * （{@link #queryTotal}/{@link #queryData} 走 CultureMapper.xml 的 whereSql、
 * {@link #findDetailById}、{@link #queryExportBatch}、统计类 queryTotalXxx）
 * <b>绝不能</b>无条件加 status=1 —— 后台列表 ?status=0（草稿箱）、后台详情、
 * 版本回滚、导出都必须能看到草稿 / 已下架 / 定时待发布内容。</p>
 *
 * <p>前后台共用的列表因此不在这里改：由前台入口（ApiHomeController#cultureList）
 * 强制 {@code query.setStatus(1)} 复用 whereSql 里已有的动态 {@code and u.status = #{status}}，
 * 后台入口不设置 → 行为逐字不变。</p>
 */
@Mapper
public interface CultureMapper {

    /**
     * 通用文化列映射（<b>详情</b>用）：新库列 -&gt; 旧实体属性（desc/view 是 MySQL 保留字，加反引号）
     *
     * <p>增量：末尾追加 status 与 publish_at（映射到 Culture.status / Culture.publishAt）。
     * 这两个字段是「草稿箱 + 定时发布」编辑回填所必需的，只在<b>详情</b>查询带出；
     * 列表用的 {@link #LIST_COLS} 与 queryData 的显式列清单都<b>不含</b>它们，
     * 因此 /api/culture/list 等既有列表接口的响应结构不变。</p>
     */
    String COLS = "id, name as cultureName, address, description as `desc`, content as info, " +
            "cover_url as fmUrl, category_id as categoryId, creator_id as creatorId, " +
            "view_count as `view`, like_count as likeCount, created_at as createTime, " +
            "status, publish_at as publishAt";

    /**
     * 列表/卡片列映射（<b>瘦身</b>用）：<b>不含 longtext 正文 content</b>，
     * 只带一个受限长度的摘要 infoSummary = LEFT(content, 300)。
     *
     * <p>为什么单独抽一个常量：正文只有详情接口（{@link #findDetailById}）需要，
     * 首页热门/文化列表/搜索/标签页/我的收藏/推荐位这些列表场景都用不到全文。
     * 之前列表 SQL 也把 content 全拉出来，一条长文就是几十 KB，响应体因此非常大。
     * 其余列与 {@link #COLS} 完全一致，所以列表字段向后兼容：只少了大字段 info、多了 infoSummary。</p>
     */
    String LIST_COLS = "id, name as cultureName, address, description as `desc`, " +
            "LEFT(content, 300) as infoSummary, " +
            "cover_url as fmUrl, category_id as categoryId, creator_id as creatorId, " +
            "view_count as `view`, like_count as likeCount, created_at as createTime";

    //查询最新3条（当前无调用方；本次不做改动，若将来给前台用必须自带 status=1）
    @Select("select " + LIST_COLS + " from biz_culture where deleted=0 order by created_at desc limit 0,3")
    List<Culture> queryAll();

    //查询所有（前台首页「今日文化」用：只取已发布 status=1；唯一调用方 ApiHomeController#pickCultureOfToday）
    @Select("select " + LIST_COLS + " from biz_culture where deleted=0 and status=1")
    List<Culture> findAll();

    //查询热门（前台首页热门卡片：只取已发布 status=1）
    //增量（推荐位/置顶）：排序改为 is_top desc, recommend_sort asc, view_count desc。
    //  存量数据 is_top 全为 0、recommend_sort 全为 0（见 docs/sql/12_recommend.sql），
    //  因此未做任何运营设置时排序结果与改造前逐字一致（仍等价于 view_count desc）。
    @Select("select " + LIST_COLS + " from biz_culture where deleted=0 and status=1 " +
            "order by is_top desc, recommend_sort asc, view_count desc limit 0,3")
    List<Culture> queryHotAll();

    //添加culture
    Integer addCulture(Culture culture);

    //根据culture id更新封面图
    void updateCultureFmUrl(Culture culture);

    //查询总的条数
    Long queryTotal(CultureQuery cultureQuery);

    //分页查询数据（前后台共用：列清单刻意不含 status/publish_at，前台响应结构不变）
    List<Culture> queryData(CultureQuery cultureQuery);

    /**
     * 后台专用分页查询（增量：草稿箱体验）。
     *
     * <p>SQL 见 CultureMapper.xml 的 queryAdminPage：与 {@link #queryData} 的
     * <b>join、whereSql 动态筛选（名称 / 分类 / 状态 / 置顶）、排序、分页逐字相同</b>，
     * 唯一差异是列清单末尾额外取 {@code u.status, u.publish_at, u.is_top, u.recommend_sort}
     * （映射到 {@link Culture#getStatus()} / {@link Culture#getPublishAt()} /
     * {@link Culture#getIsTop()} / {@link Culture#getRecommendSort()}）。</p>
     *
     * <p>为什么不直接改 {@link #queryData}：它是前后台共用方法
     * （{@code ApiHomeController#cultureList} 也走它），给它加列会让前台列表接口
     * 凭空多出 status/publishAt 字段。因此复制一条后台专用查询，前台那条一字不动。</p>
     *
     * <p>唯一调用方：{@code CultureServiceImpl#listpageForAdmin}（即 /api/admin/culture/list）。</p>
     */
    List<Culture> queryAdminPage(CultureQuery cultureQuery);

    //删除culture（逻辑删除）
    void deleteCulture(Long id);

    //修改culture
    void editSaveCulture(Culture culture);

    //查询culture详细信息（后台编辑回填 / 版本快照 / 封面上传校验共用：**刻意不过滤 status**，草稿也要能看）
    @Select("select " + COLS + " from biz_culture where id=#{id} and deleted=0")
    Culture findDetailById(Long id);

    /**
     * 前台详情专用：只返回<b>已发布</b>（status=1）的内容。
     *
     * <p>为什么不直接在 {@link #findDetailById} 上加 status=1：那是前后台共用方法——
     * 后台详情 /api/admin/culture/detail、版本快照（CultureVersionServiceImpl）与封面上传校验
     * （FileUpload）都必须能看到草稿 / 定时待发布内容，加了会把后台一起弄坏。
     * 因此前台单独走这一条：SQL 与 findDetailById 完全一致，只多一个 status=1，响应字段不变。</p>
     *
     * <p>调用方：ApiHomeController#cultureDetail（查不到返回 404「内容不存在」）、#pickCultureOfToday。</p>
     */
    @Select("select " + COLS + " from biz_culture where id=#{id} and deleted=0 and status=1")
    Culture findPublishedDetailById(Long id);

    //更新浏览量
    @Update("update biz_culture set view_count=view_count+1 where id=#{id}")
    void updateViewNum(Long id);

    //查询用户喜欢的culture
    @Select("select id, user_id uid, target_id bid, value val, created_at createTime " +
            "from biz_like where user_id=#{uid}")
    List<Like> findUserLikeCulture(@Param("uid") Long uid);

    //浏览最多的10条（前台详情页推荐位/推荐兜底：只取已发布 status=1）
    //增量（推荐位/置顶）：排序与 queryHotAll 保持同一套规则（置顶 → 推荐位 → 浏览量）。
    @Select("select " + LIST_COLS + " from biz_culture where deleted=0 and status=1 " +
            "order by is_top desc, recommend_sort asc, view_count desc limit 0,10")
    List<Culture> findTop4Culture();

    //通过id查询culture
    @Select("select " + COLS + " from biz_culture where id=#{id} and deleted=0")
    Culture findCultureById(Long id);

    //判断用户是否收藏culture（存在即已收藏）
    @Select("select id, user_id uid, target_id bid, value val from biz_like " +
            "where user_id=#{userid} and target_id=#{bid}")
    Culture queryExistScCulture(Long userid, Long bid);

    //收藏culture
    @Insert("insert into biz_like (user_id,target_id,value) values(#{userid},#{bid},5)")
    void scCultureByUser(Long userid, Long bid);

    //查询用户收藏的文化（前台个人中心卡片列表：只取已发布 status=1，已下架/草稿不出现）
    @Select("select " + LIST_COLS + " from biz_culture where deleted=0 and status=1 and id in " +
            "(select target_id from biz_like where user_id=#{userid})")
    List<Culture> findMyScCulture(Long userid);

    //取消收藏
    @Delete("delete from biz_like where user_id=#{userid} and target_id=#{bid}")
    void deleteScCulture(Long userid, Long bid);

    //总浏览量
    @Select("select sum(view_count) from biz_culture where deleted=0")
    Long queryTotalViewNum();

    //总culture数
    @Select("select count(*) from biz_culture where deleted=0")
    Long queryTotalCultureNum();

    //总注册用户数（不含逻辑删除）
    @Select("select count(*) from sys_user where deleted=0")
    Long queryTotalUserRegNum();

    //总收藏数
    @Select("select count(*) from biz_like")
    Long queryTotalScNum();

    //同分类推荐（排除自身，按浏览量倒序）——供详情页「猜你喜欢」使用（前台：只取已发布 status=1）
    @Select("select " + LIST_COLS + " from biz_culture where deleted=0 and status=1 and category_id=#{categoryId} " +
            "and id<>#{excludeId} order by view_count desc limit #{limit}")
    List<Culture> findRecommendByCategory(@Param("categoryId") Long categoryId,
                                          @Param("excludeId") Long excludeId,
                                          @Param("limit") int limit);

    // ===================== 全站搜索 =====================
    // 说明：kw 由 SearchUtil.prepareLikePattern 预转义（% / _ / ! 已带转义符），
    //      因此 SQL 里必须声明 `escape '!'`，否则转义符会被当作普通字符。
    // 前台可见性：这四个搜索查询只被 ApiSearchController（前台匿名搜索）使用，
    //      因此统一带 status=1，草稿/已下架/定时未到点的内容不会出现在搜索结果里。
    //
    // 【增量】服务端组合筛选（分类 / 标签 / 时间范围）：
    //   · 四个搜索语句（LIKE 总数 / LIKE 分页 / 全文总数 / 全文分页）共用下面同一个
    //     SEARCH_FILTER_SQL 片段 —— 总数与列表的筛选条件因此逐字一致，
    //     不会出现「总数与列表打架」的经典分页 bug；
    //   · 片段由 <if> 动态拼接：四个筛选参数全为 null 时拼接结果为空串，
    //     生成的 SQL 与改造前逐字等价（只多了表别名 c，语义不变），
    //     所以不传筛选参数的调用方行为完全不变；
    //   · biz_culture 统一取别名 c（exists 子查询要引用 c.id）。

    /**
     * 搜索组合筛选片段（分类 / 标签 / 时间范围），四个搜索语句共用。
     *
     * <p>· 分类：{@code c.category_id = #{categoryId}}；</p>
     * <p>· 标签：用 {@code exists} 相关子查询而不是 join biz_culture_tag ——
     * 一条内容可以有多个标签，join 会让同一行出现多次，count(*) 虚高、limit 分页出现重复条目；
     * exists 只做「存在性」判断，天然去重。子查询能吃到
     * {@code idx_culture_tag_tag_culture(tag_id, culture_id)}，不会退化成全表扫描。</p>
     * <p>· 时间：作用在 {@code c.created_at} 上，闭区间 {@code [startTime, endTime]}，
     * 传入的已是 {@link com.culture.util.SearchUtil#normalizeStartTime} /
     * {@link com.culture.util.SearchUtil#normalizeEndTime} 规范化后的
     * {@code yyyy-MM-dd HH:mm:ss} 字符串。用字符串参数的好处见 SearchUtil 的注释：
     * MySQL 对「DATETIME 列 vs 字符串常量」的比较是把字符串按字面解析成 DATETIME，
     * 不做时区换算，且转换只发生在常量侧，因此仍能走 created_at 上的索引
     * （实测见交付报告的 EXPLAIN）。</p>
     * <p>· XML 转义：SQL 里的小于等于必须写成实体形式，否则用 &lt;script&gt; 包起来的
     * 这段动态 SQL 在 MyBatis 解析阶段就会报 XML 语法错误。</p>
     */
    String SEARCH_FILTER_SQL =
            "<if test='categoryId != null'> and c.category_id = #{categoryId}</if>" +
                    "<if test='tagId != null'> and exists (select 1 from biz_culture_tag t " +
                    "where t.culture_id = c.id and t.tag_id = #{tagId})</if>" +
                    "<if test='startTime != null'> and c.created_at &gt;= #{startTime}</if>" +
                    "<if test='endTime != null'> and c.created_at &lt;= #{endTime}</if>";

    //搜索命中的文化总数（name / description / content 三字段模糊匹配，含逻辑删除与已发布过滤）
    //【增量】末尾追加 4 个可选筛选参数（见 SEARCH_FILTER_SQL），全为 null 时 SQL 与改造前一致
    @Select("<script>select count(*) from biz_culture c where c.deleted=0 and c.status=1 and (" +
            "c.name like #{kw} escape '!' or c.description like #{kw} escape '!' or c.content like #{kw} escape '!')" +
            SEARCH_FILTER_SQL +
            "</script>")
    Long querySearchTotal(@Param("kw") String kw,
                          @Param("categoryId") Long categoryId,
                          @Param("tagId") Long tagId,
                          @Param("startTime") String startTime,
                          @Param("endTime") String endTime);

    //搜索命中的文化分页数据：名称命中优先，其次按浏览量倒序（id 兜底，保证分页稳定）
    //（列表场景：不取 longtext 正文，只带 LEFT(content,300) 摘要）
    //【增量】筛选条件与 querySearchTotal 共用 SEARCH_FILTER_SQL，保证总数与列表口径一致
    @Select("<script>select " + LIST_COLS + " from biz_culture c where c.deleted=0 and c.status=1 and (" +
            "c.name like #{kw} escape '!' or c.description like #{kw} escape '!' or c.content like #{kw} escape '!')" +
            SEARCH_FILTER_SQL +
            " order by (c.name like #{kw} escape '!') desc, c.view_count desc, c.id desc " +
            "limit #{offset}, #{pageSize}</script>")
    List<Culture> querySearchData(@Param("kw") String kw,
                                  @Param("offset") Integer offset,
                                  @Param("pageSize") Integer pageSize,
                                  @Param("categoryId") Long categoryId,
                                  @Param("tagId") Long tagId,
                                  @Param("startTime") String startTime,
                                  @Param("endTime") String endTime);

    // ===================== 全站搜索：ngram 全文检索（增量） =====================
    // 与上面两个 LIKE 查询「返回字段 / 过滤条件（含组合筛选片段 SEARCH_FILTER_SQL）/ 排序 / 分页」
    // 完全一致，只有「怎么找」不同：
    //   MATCH(c.name, c.description, c.content) AGAINST('"关键词"' IN BOOLEAN MODE)
    // 索引与解析器见 docs/sql/10_search.sql（biz_culture 上的 ft_culture_search）。
    //
    // · kw     = SearchUtil.toBooleanPhrase(keyword)，形如 "博物馆"（已剔除 BOOLEAN 操作符）。
    //            用引号短语而不是裸词，是因为 ngram 下裸词是「token 或」语义会误命中；
    //            为什么选 BOOLEAN MODE 而不是 NATURAL LANGUAGE MODE 见 10_search.sql 注释。
    // · likeKw = SearchUtil.toLikePattern(keyword)，形如 %博物馆%，只用于「名称命中优先」排序。
    //            MATCH() 的列清单必须与 FULLTEXT 索引列完全一致，不能单独 MATCH(name)，
    //            所以名称优先级仍用 LIKE，且只作用于全文命中后的少量候选行。
    //
    // 索引不存在时 MySQL 报 ERROR 1191 Can't find FULLTEXT index matching the column list，
    // 由 Service 捕获后降级回 querySearchTotal / querySearchData（搜索接口不会 500）。

    /** 全文检索命中的文化总数（与下面分页查询用同一 MATCH 条件 + 同一筛选片段，避免总数与列表对不上；含 status=1） */
    @Select("<script>select count(*) from biz_culture c where c.deleted=0 and c.status=1 and " +
            "MATCH(c.name, c.description, c.content) AGAINST(#{kw} IN BOOLEAN MODE)" +
            SEARCH_FILTER_SQL +
            "</script>")
    Long querySearchTotalByFullText(@Param("kw") String kw,
                                    @Param("categoryId") Long categoryId,
                                    @Param("tagId") Long tagId,
                                    @Param("startTime") String startTime,
                                    @Param("endTime") String endTime);

    /** 全文检索命中的文化分页数据（字段/过滤/排序与 querySearchData 一致，前端无感；含 status=1 与同一筛选片段） */
    @Select("<script>select " + LIST_COLS + " from biz_culture c where c.deleted=0 and c.status=1 and " +
            "MATCH(c.name, c.description, c.content) AGAINST(#{kw} IN BOOLEAN MODE)" +
            SEARCH_FILTER_SQL +
            " order by (c.name like #{likeKw} escape '!') desc, c.view_count desc, c.id desc " +
            "limit #{offset}, #{pageSize}</script>")
    List<Culture> querySearchDataByFullText(@Param("kw") String kw,
                                            @Param("likeKw") String likeKw,
                                            @Param("offset") Integer offset,
                                            @Param("pageSize") Integer pageSize,
                                            @Param("categoryId") Long categoryId,
                                            @Param("tagId") Long tagId,
                                            @Param("startTime") String startTime,
                                            @Param("endTime") String endTime);

    //按id集合批量查询（前台详情页「猜你喜欢」协同过滤推荐：不取正文，只取已发布 status=1）
    //调用方确认：唯一调用方是 RecommendServiceImpl（前台推荐），后台导出/版本回滚均不使用它。
    @Select({
            "<script>" +
                    "select " + LIST_COLS + " from biz_culture where deleted=0 and status=1 and id in " +
                    "<foreach item='item' index ='index' collection = 'Ids' open='(' separator=',' close=')'>" +
                    "#{item}" +
                    "</foreach>" +
                    "</script>"})
    List<Culture> findAllByIds(@Param("Ids") List<Long> itemIds);

    // ===================== SEO 专用查询（SQL 见 CultureMapper.xml 的 SeoCultureMap） =====================
    // 这三个方法只给 SeoController 用：会额外带出 updated_at（映射到 Culture.updateTime），
    // 而 COLS 里刻意不含 updated_at，所以既有接口的响应不会多出字段。
    // 前台可见性：SEO 面向搜索引擎/爬虫，SQL 里统一过滤 deleted=0 且 status=1，
    // 草稿 / 已下架 / 定时未到点的内容不会进入 sitemap / rss / meta。

    /** sitemap：全部<b>已发布</b>内容的 id 与创建/更新时间（不取正文，避免整表 longtext 传输） */
    List<Culture> findSeoList();

    /** rss：最新 limit 条<b>已发布</b>内容（含描述/正文/作者，用于生成纯文本摘要） */
    List<Culture> findSeoLatest(@Param("limit") int limit);

    /** meta：单条<b>已发布</b>内容的 SEO 字段（含作者用户名，左连接，作者缺失也不影响内容） */
    Culture findSeoDetail(@Param("id") Long id);

    // ===================== 后台批量操作 / CSV 导出（增量追加） =====================
    // SQL 见 CultureMapper.xml（批量 in 列表 + 导出分批查询），此处只声明接口。

    /** 批量逻辑删除（deleted=1），返回实际更新条数；ids 由 Service 保证非空且 ≤ 500 */
    int batchDelete(@Param("ids") List<Long> ids);

    /** 批量修改分类，返回实际更新条数；分类是否存在由 Service 先行校验 */
    int batchUpdateCategory(@Param("ids") List<Long> ids, @Param("categoryId") Long categoryId);

    /**
     * 导出专用分批查询：只取导出列（不含 longtext 正文），条件与后台列表一致。
     * 返回 LinkedHashMap，键为 XML 中的列别名（id/cultureName/authorName/categoryName/status/createTime/updateTime）。
     */
    List<Map<String, Object>> queryExportBatch(@Param("q") CultureQuery query,
                                               @Param("offset") int offset,
                                               @Param("pageSize") int pageSize);

    // ===================== 删除可撤销：恢复（增量追加） =====================
    // SQL 见 CultureMapper.xml 的 restoreById / restoreByIds：
    // 只把 deleted 从 1 改回 0，且必须带 deleted = 1 条件（避免把正常记录也「恢复」）。

    /** 恢复单条被逻辑删除的文化，返回实际影响行数（0 表示该 id 不存在或未删除） */
    int restoreById(@Param("id") Long id);

    /** 批量恢复被逻辑删除的文化，返回实际影响行数；ids 由 Service 保证非空且 ≤ 500 */
    int restoreByIds(@Param("ids") List<Long> ids);

    // ===================== 定时发布 + 草稿箱（增量追加） =====================
    // status 语义：0=草稿、1=已发布、2=定时待发布（历史数据仍全部为 1，本增量不改动任何既有数据）。
    // SQL 见 CultureMapper.xml 的 batchUpdateStatus / publishScheduled / applyVersion。

    /**
     * 批量修改状态（后台 /api/admin/culture/batch-status）。
     * 只改 status 一列，<b>不动 publish_at</b>；返回实际更新行数。
     * 3 个合法值（0/1/2）由 Service 校验，非法值不会走到这里。
     */
    int batchUpdateStatus(@Param("ids") List<Long> ids, @Param("status") Integer status);

    /**
     * 定时发布扫描（PublishScheduler 每分钟调用）：
     * {@code status=2 and deleted=0 and publish_at <= now()} → 批量置为 status=1，返回更新行数。
     * 条件全部写在 SQL 里（单条 UPDATE，天然并发安全，不需要先查 id 再逐条更新）。
     */
    int publishScheduled();

    /**
     * 版本回滚：把某个版本快照的内容写回 biz_culture。
     * <b>只还原版本表里有的 5 个字段</b>（name/description/content/cover_url/category_id），
     * status / publish_at / address 不参与回滚；只作用于 deleted=0 的记录。
     * 调用前调用方（CultureVersionServiceImpl）已先写过「当前内容」的快照。
     *
     * @return 实际更新行数（0 表示内容不存在或已被逻辑删除）
     */
    int applyVersion(@Param("id") Long id,
                     @Param("name") String name,
                     @Param("description") String description,
                     @Param("content") String content,
                     @Param("coverUrl") String coverUrl,
                     @Param("categoryId") Long categoryId);

    // ===================== 推荐位 / 置顶（增量追加，见 docs/sql/12_recommend.sql） =====================
    // 只有这两个写方法 + 上面两处前台排序改动；后台列表/导出的筛选与排序一律不动。

    /**
     * 批量置顶 / 取消置顶（后台 POST /api/admin/culture/top，body {@code {ids:[...], isTop:0|1}}）。
     *
     * <p>只改 {@code is_top} 一列，<b>不动 recommend_sort / status / publish_at</b>：
     * 取消置顶后再置顶回来，原来的推荐位顺序仍然保留。</p>
     *
     * <p>SQL 见 CultureMapper.xml#batchUpdateTop：带 {@code deleted = 0} 条件，
     * 逻辑删除的内容不会被误改；{@code isTop} 只允许 0/1（Service 校验），
     * ids 由 Service 保证非空、已去重且 ≤ 500 条。</p>
     *
     * @return 实际更新行数（不存在的 id / 已逻辑删除的 id 自动忽略）
     */
    int batchUpdateTop(@Param("ids") List<Long> ids, @Param("isTop") Integer isTop);

    /**
     * 调整单条内容的推荐位顺序（后台 POST /api/admin/culture/recommend-sort，
     * body {@code {id, recommendSort}}）。
     *
     * <p>只改 {@code recommend_sort} 一列，<b>不动 is_top</b>：运营可以先把内容置顶，
     * 再在置顶集合内调整先后（越小越靠前）。允许 0 与负数（负数同样排在最前，
     * 便于「插到最前面」而不必重排全部数据）。</p>
     *
     * <p>SQL 见 CultureMapper.xml#updateRecommendSort：带 {@code deleted = 0} 条件，
     * 影响行数 0 表示 id 不存在或已被逻辑删除（后台接口据此返回 404 业务错误）。</p>
     *
     * @return 实际更新行数（0 或 1）
     */
    int updateRecommendSort(@Param("id") Long id, @Param("recommendSort") Integer recommendSort);
}
