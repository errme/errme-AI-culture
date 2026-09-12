package com.culture.service;



import com.culture.entity.Culture;
import com.culture.entity.Like;
import com.culture.query.CultureQuery;
import com.culture.util.PageList;
import com.culture.entity.Culture;

import java.util.List;
import java.util.Map;


public interface CultureService {

    //查询所有culture
    List<Culture> queryAll();

    List<Culture> findAll();

    //添加culture
    Integer addCulture(Culture culture);

    //根据id更新封面图
    void updateCultureFmUrl(Culture culture);

    /**
     * 分页列表（前后台共用）：走 {@code CultureMapper.queryData}，
     * 返回字段刻意<b>不含</b> status / publishAt，前台接口 /api/culture/list 的响应结构因此不变。
     */
    public PageList listpage(CultureQuery cultureQuery);

    /**
     * 后台专用分页列表（增量：草稿箱体验）。
     *
     * <p>与 {@link #listpage(CultureQuery)} 的唯一区别是数据行改用
     * {@code CultureMapper.queryAdminPage}：在 queryData 的列基础上额外返回
     * {@code status} 与 {@code publishAt}，供后台列表的「状态」列渲染
     * 草稿 / 已发布 / 定时徽章；总数、动态筛选（名称 / 分类 / status）、排序、分页、
     * 缩略图派生与 {@link #listpage(CultureQuery)} 完全一致。</p>
     *
     * <p>为什么用独立方法而不是给 listpage 加布尔开关：前台入口是
     * {@code ApiHomeController#cultureList}，让它继续调用原来的 listpage，
     * 前台路径<b>零改动、零回归风险</b>；布尔开关则要求调用方理解参数含义，
     * 且一旦误传就会让前台接口多出字段。唯一调用方：{@code ApiAdminController#cultureList}。</p>
     */
    public PageList listpageForAdmin(CultureQuery cultureQuery);

    void deleteCulture(Long id);

    void editSaveCulture(Culture culture);

    /**
     * 修改保存（增量重载：带操作人，用于版本留痕）。
     *
     * <p>与 {@link #editSaveCulture(Culture)} 的唯一区别是多带一个操作人信息，
     * 写进 biz_culture_version.operator_id / operator_name。
     * 快照写在 update <b>之前</b>，且快照失败不影响主保存流程。</p>
     *
     * @param culture      文化记录（id 必填）
     * @param operatorId   操作人 id（后台 JWT 取 request 属性 loginUserId）
     * @param operatorName 操作人用户名；为 null 时按 operatorId 反查 sys_user
     */
    void editSaveCulture(Culture culture, Long operatorId, String operatorName);

    /**
     * 批量修改状态（增量）：0=草稿、1=已发布、2=定时待发布。
     *
     * @param ids    文化 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException（控制层转 400）
     * @param status 目标状态；不是 0/1/2 时抛 BusinessException（控制层转 400）
     * @return 实际更新条数
     */
    int batchUpdateStatus(List<Long> ids, Integer status);

    /**
     * 批量置顶 / 取消置顶（增量：内容推荐位/置顶，见 docs/sql/12_recommend.sql）。
     *
     * <p>只改 {@code is_top} 一列，不动 recommend_sort / status / publish_at。
     * 生效位置：前台首页热门（{@code queryHotAll}）与详情页推荐（{@code findTop4Culture}）
     * 的排序首位 {@code is_top desc}。后台列表排序（u.id desc）不受影响。</p>
     *
     * @param ids   文化 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException（控制层转 400）
     * @param isTop 0=取消置顶、1=置顶；其它值抛 BusinessException（控制层转 400）
     * @return 实际更新条数（不存在的 id 会被忽略）
     */
    int batchUpdateTop(List<Long> ids, Integer isTop);

    /**
     * 调整单条内容的推荐位顺序（增量，越小越靠前）。
     *
     * @param id            文化 id；为 null 时抛 BusinessException
     * @param recommendSort 推荐位顺序值（允许 0 与负数，便于插到最前）；为 null 时抛 BusinessException
     * @return 实际更新条数；0 表示 id 不存在或已被逻辑删除（控制层转 404 业务错误）
     */
    int updateRecommendSort(Long id, Integer recommendSort);

    //查询热门（前台首页热门卡片：只含已发布 status=1）
    List<Culture> queryHotCulture();

    /**
     * 详情（前后台共用）：<b>不过滤 status</b>。
     * 后台 /api/admin/culture/detail、版本快照、封面上传校验依赖它看到草稿 / 定时待发布内容。
     */
    Culture findDetailById(Long id);

    /**
     * 前台详情：只返回<b>已发布</b>（status=1）的内容，查不到返回 null
     * （ApiHomeController 转 404「内容不存在」）。
     *
     * <p>与 {@link #findDetailById} 的唯一区别就是多过滤 status=1，响应字段完全一致。
     * 前台的其它入口（首页今日文化、sitemap/rss/meta、搜索、标签页、收藏、推荐）
     * 也都不返回草稿 / 已下架 / 定时未到点的内容。</p>
     */
    Culture findPublishedDetailById(Long id);

    /** 详情页推荐位：同分类优先，不足时用热门补齐（排除当前文章）；只推荐已发布内容 */
    List<Culture> findRecommendCultures(Culture current, int limit);

    //更新浏览量
    void updateViewNum(Long id);

    //获取用户推荐
    List<Culture> getUserTjCulture(String username,Long cultureId);

    boolean isExistScCulture(Long userid, Long bid);

    void scCulture(Long userid, Long bid);


    List<Culture> findMyScCulture(Long id);

    void cancelScCulture(Long id, Long id1);

    Long queryTotalViewNum();

    Long queryTotalCultureNum();

    Long queryTotalUserRegNum();

    Long queryTotalScNum();

    //全站搜索：命中的文化总数（keyword 传原始值，Impl 内部做 trim/截断/LIKE 转义）
    Long querySearchTotal(String keyword);

    //全站搜索：分页取文化数据（名称命中优先，其次浏览量倒序）
    List<Culture> querySearchData(String keyword, Integer offset, Integer pageSize);

    /**
     * 【增量】全站搜索（文化）带组合筛选的命中总数。
     *
     * <p>四个筛选参数都可为 null（= 不启用该筛选）；全部为 null 时与
     * {@link #querySearchTotal(String)} 逐字同一条 SQL、结果完全一致。
     * 筛选值由控制层用 {@code SearchUtil} 规范化后再传进来
     * （时间已转成 {@code yyyy-MM-dd HH:mm:ss} 字符串，非法值传 null）。</p>
     *
     * @param categoryId 分类 id，null = 不限（对应 biz_culture.category_id）
     * @param tagId      标签 id，null = 不限（exists biz_culture_tag）
     * @param startTime  创建时间闭区间起点（含），null = 不限
     * @param endTime    创建时间闭区间终点（含），null = 不限
     */
    Long querySearchTotal(String keyword, Long categoryId, Long tagId, String startTime, String endTime);

    /**
     * 【增量】全站搜索（文化）带组合筛选的分页数据。
     *
     * <p>筛选口径与 {@link #querySearchTotal(String, Long, Long, String, String)} 完全一致
     * （Mapper 里共用同一段动态 SQL），因此总数与列表不会打架。
     * 四个筛选参数全为 null 时与 {@link #querySearchData(String, Integer, Integer)} 等价。</p>
     */
    List<Culture> querySearchData(String keyword, Integer offset, Integer pageSize,
                                  Long categoryId, Long tagId, String startTime, String endTime);

    //SEO：sitemap 用，取全部未删除内容的 id 与时间（含 updateTime，不含正文）
    List<Culture> findSeoList();

    //SEO：rss 用，取最新 limit 条未删除内容（含描述/正文/作者）
    List<Culture> findSeoLatest(int limit);

    //SEO：meta 用，按 id 取单条未删除内容的 SEO 字段
    Culture findSeoDetail(Long id);

    // ===================== 后台批量操作 / CSV 导出（增量追加） =====================

    /**
     * 批量逻辑删除（deleted=1，与单条 deleteCulture 语义一致）。
     *
     * @param ids 文化 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException
     * @return 实际更新条数
     */
    int batchDelete(List<Long> ids);

    /**
     * 批量修改分类。<b>先校验 categoryId 存在（未逻辑删除）</b>，不存在抛 BusinessException
     * （控制层转成 400 业务错误，不会 500）。
     *
     * @param ids        文化 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException
     * @param categoryId 目标分类 id
     * @return 实际更新条数
     */
    int batchUpdateCategory(List<Long> ids, Long categoryId);

    /**
     * 导出专用分批查询：条件与后台列表一致（cultureName 模糊 / categoryId），
     * <b>只取导出需要的列</b>（不查 longtext 正文 content），供流式导出按批调用。
     *
     * @param query    筛选条件（cultureName、categoryId；categoryId=-1 视为全部）
     * @param offset   起始行
     * @param pageSize 本批条数
     * @return 每行一个 Map（id / cultureName / authorName / categoryName / status / createTime / updateTime）
     */
    List<Map<String, Object>> queryExportBatch(CultureQuery query, int offset, int pageSize);

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /**
     * 恢复单条被逻辑删除的文化（撤销删除：deleted 1 → 0，其它字段一律不动）。
     * 权限校验与删除一致，由控制层（/api/admin/culture/restore，要求管理员）负责。
     *
     * @param id 文化 id；为 null 时抛 BusinessException（控制层转 400）
     * @return 实际影响行数；0 表示该 id 不存在或本来就没被删除
     */
    int restore(Long id);

    /**
     * 批量恢复被逻辑删除的文化。
     *
     * @param ids 文化 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException（控制层转 400）
     * @return 实际影响行数；未删除/不存在的 id 会被忽略
     */
    int restoreBatch(List<Long> ids);
}
