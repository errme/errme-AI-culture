package com.culture.service.impl;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Category;
import com.culture.entity.Culture;
import com.culture.entity.User;
import com.culture.mapper.CultureMapper;
import com.culture.mapper.UserMapper;
import com.culture.query.CultureQuery;
import com.culture.service.CategoryService;
import com.culture.service.CultureService;
import com.culture.service.CultureVersionService;
import com.culture.service.RecommendService;
import com.culture.service.ThumbnailService;
import com.culture.util.CommonUtil;
import com.culture.util.PageList;
import com.culture.util.SearchUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CultureServiceImpl implements CultureService {

    @Autowired
    private CultureMapper cultureMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RecommendService recommendService;

    /** 列表缩略图派生（A3）：列表接口把 fmUrl 换成 xxx_thumb.jpg，详情接口不动 */
    @Autowired
    private ThumbnailService thumbnailService;

    /**
     * 内容版本历史（保存即留痕）：修改保存前写「修改前」快照。
     * 该服务内部吞掉全部异常，版本表不可用也不会影响保存文化。
     */
    @Autowired
    private CultureVersionService cultureVersionService;

    /** 推荐结果简单缓存：userId -> 条目（TTL 10 分钟，避免每次打开详情页都实时跑 Mahout） */
    private static final long RECOMMEND_CACHE_TTL_MS = 10 * 60 * 1000;
    private final Map<Long, RecommendCacheEntry> recommendCache = new ConcurrentHashMap<>();

    /** 单次批量操作上限（与前端「一次最多选 500 条」对齐） */
    private static final int MAX_BATCH_SIZE = 500;

    /** 分类存在性校验用（分类是极小表，queryAll 只返回未逻辑删除的分类） */
    @Autowired
    private CategoryService categoryService;

    private static class RecommendCacheEntry {
        final List<Culture> items;
        final long expireAt;
        RecommendCacheEntry(List<Culture> items) {
            this.items = items;
            this.expireAt = System.currentTimeMillis() + RECOMMEND_CACHE_TTL_MS;
        }
        boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    @Override
    public List<Culture> queryAll() {
        return cultureMapper.queryAll();
    }

    @Override
    public List<Culture> findAll() {
        return cultureMapper.findAll();
    }

    //添加culture
    @Override
    public Integer addCulture(Culture culture) {
        // Session 登录时取当前用户；JWT 调用方已预先设置 creatorId，这里不覆盖
        if (culture.getCreatorId() == null && CommonUtil.getLoginUser() != null) {
            culture.setCreatorId(CommonUtil.getLoginUser().getId());
        }
        if (culture.getView() == null) culture.setView(0L);
        if (culture.getCreateTime() == null) culture.setCreateTime(new Date());
        return cultureMapper.addCulture(culture);
    }

    @Override
    public void updateCultureFmUrl(Culture culture) {
        cultureMapper.updateCultureFmUrl(culture);
    }

    @Override
    public PageList listpage(CultureQuery cultureQuery) {
        return listpage(cultureQuery, false);
    }

    /**
     * 后台专用分页列表（增量：草稿箱体验）。
     * 与 {@link #listpage(CultureQuery)} 走同一套总数/缩略图/分页组装，只是数据行改用
     * {@code queryAdminPage} → 额外带出 status / publishAt（前台接口不返回这两个字段）。
     */
    @Override
    public PageList listpageForAdmin(CultureQuery cultureQuery) {
        return listpage(cultureQuery, true);
    }

    /**
     * 列表组装的共同实现。
     *
     * @param forAdmin false = 前后台共用的 queryData（列清单不含 status/publishAt，前台接口字段不变）；
     *                 true  = 后台专用的 queryAdminPage（多返回 status/publishAt）。
     *                 两条 SQL 的 join / whereSql 动态筛选 / 排序 / 分页完全一致，总数也共用 queryTotal。
     */
    private PageList listpage(CultureQuery cultureQuery, boolean forAdmin) {
        PageList pageList = new PageList();
        //查询总的条数
        Long total = cultureMapper.queryTotal(cultureQuery);
        List<Culture> cultures = forAdmin
                ? cultureMapper.queryAdminPage(cultureQuery)
                : cultureMapper.queryData(cultureQuery);
        // A3：列表卡片改用缩略图（fmUrl 指向 xxx_thumb.jpg，原图在 coverOriginal）；
        //     前端/后台的 coverUrl(fmUrl) 无需改动，详情接口仍返回原图
        thumbnailService.applyCoverThumb(cultures);
        pageList.setTotal(total);
        pageList.setRows(cultures);
        //分页查询的数据
        return pageList;
    }

    //删除culture
    @Override
    public void deleteCulture(Long id) {
        cultureMapper.deleteCulture(id);
    }

    //修改culture（保留原签名：不带操作人，兼容老调用方）
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void editSaveCulture(Culture culture) {
        editSaveCulture(culture, null, null);
    }

    /**
     * 修改保存（增量重载：带操作人，写版本快照）。
     *
     * <p>顺序很重要：<b>先快照、后更新</b>——快照读到的必须是「修改前」的整条记录。
     * 快照走 {@link CultureVersionService#snapshotBeforeSave}，
     * 内部 try/catch + 日志，同一自然分钟内只留一条，失败绝不影响本次保存。</p>
     *
     * <p>本方法带 @Transactional：快照与更新在同一个事务里，
     * 保存失败时不会留下「有版本、没保存」的脏数据。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void editSaveCulture(Culture culture, Long operatorId, String operatorName) {
        if (culture != null && culture.getId() != null) {
            cultureVersionService.snapshotBeforeSave(culture.getId(), operatorId, operatorName);
        }
        cultureMapper.editSaveCulture(culture);
    }

    /**
     * 批量修改状态（增量）：0=草稿、1=已发布、2=定时待发布。
     * 校验与批量删除/批量改分类保持同一套（去 null 去重、非空、≤ 500 条），失败抛 BusinessException → 400。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchUpdateStatus(List<Long> ids, Integer status) {
        if (status == null || (status != 0 && status != 1 && status != 2)) {
            throw new BusinessException("状态不合法：0=草稿、1=已发布、2=定时待发布");
        }
        List<Long> safeIds = normalizeIds(ids);
        return cultureMapper.batchUpdateStatus(safeIds, status);
    }

    /**
     * 批量置顶 / 取消置顶（增量：内容推荐位/置顶，见 docs/sql/12_recommend.sql）。
     *
     * <p>校验与批量状态一致（isTop 只允许 0/1；ids 去 null 去重、非空、≤ 500 条），
     * 失败抛 BusinessException → 控制层 400。SQL 只改 is_top 一列，
     * 因此「取消置顶再置顶」不会丢失已设置的 recommend_sort。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchUpdateTop(List<Long> ids, Integer isTop) {
        if (isTop == null || (isTop != 0 && isTop != 1)) {
            throw new BusinessException("置顶标记不合法：0=取消置顶、1=置顶");
        }
        List<Long> safeIds = normalizeIds(ids);
        return cultureMapper.batchUpdateTop(safeIds, isTop);
    }

    /**
     * 调整单条内容的推荐位顺序（增量，越小越靠前）。
     *
     * <p>不做「先查后写」：SQL 自带 {@code deleted = 0} 条件，返回 0 就代表 id 不存在或已删除
     * （控制层转 404），避免多一次查询也避免并发下的竞态。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateRecommendSort(Long id, Integer recommendSort) {
        if (id == null) {
            throw new BusinessException("参数错误：缺少文化 id");
        }
        if (recommendSort == null) {
            throw new BusinessException("参数错误：缺少推荐顺序 recommendSort");
        }
        return cultureMapper.updateRecommendSort(id, recommendSort);
    }

    //查询热门culture（前台首页热门卡片：A3 改用缩略图）
    @Override
    public List<Culture> queryHotCulture() {
        List<Culture> hot = cultureMapper.queryHotAll();
        thumbnailService.applyCoverThumb(hot);
        return hot;
    }

    //查找culture（前后台共用，故意不过滤 status：后台详情/版本快照/封面上传校验要看草稿）
    @Override
    public Culture findDetailById(Long id) {
        return cultureMapper.findDetailById(id);
    }

    //前台详情：只返回已发布（status=1）的内容，草稿/已下架/定时未到点一律查不到 → 上层 404
    @Override
    public Culture findPublishedDetailById(Long id) {
        if (id == null) return null;
        Culture culture = cultureMapper.findPublishedDetailById(id);
        // 多档缩略图（增量）：详情页/分享卡片用的大档（1200）按需生成并挂到 coverThumbLarge，
        // fmUrl 保持原图不动（详情主图不能被降质）；失败时内部回退旧档/原图，不会产生坏链。
        // 只在前台详情做：后台详情（findDetailById）与版本快照/封面上传校验不需要缩略图，
        // 避免给管理端路径增加无谓的解码开销。
        thumbnailService.applyCoverThumbLarge(culture);
        return culture;
    }

    /**
     * 详情页推荐位：同分类优先，数量不足时用热门内容补齐（都排除当前文章）。
     * 替代前端原先「取最新 4 条」的兜底逻辑。
     */
    @Override
    public List<Culture> findRecommendCultures(Culture current, int limit) {
        List<Culture> result = new java.util.ArrayList<>();
        if (current == null || limit <= 0) return result;
        if (current.getCategoryId() != null) {
            List<Culture> sameCategory = cultureMapper.findRecommendByCategory(
                    current.getCategoryId(), current.getId(), limit);
            if (sameCategory != null) result.addAll(sameCategory);
        }
        if (result.size() < limit) {
            List<Culture> hot = cultureMapper.findTop4Culture();
            if (hot != null) {
                for (Culture c : hot) {
                    if (result.size() >= limit) break;
                    if (c.getId() == null || c.getId().equals(current.getId())) continue;
                    boolean exists = result.stream().anyMatch(r -> c.getId().equals(r.getId()));
                    if (!exists) result.add(c);
                }
            }
        }
        return result;
    }

    //更新浏览量
    @Override
    public void updateViewNum(Long id) {
        cultureMapper.updateViewNum(id);
    }

    /**
     * 获取用户推荐内容（带 10 分钟进程内缓存）。
     *
     * <p><b>⚠ 当前没有任何调用方（死代码）：</b>全仓库搜索 {@code getUserTjCulture} 只有
     * 接口声明与本实现，前台详情页的「猜你喜欢」实际走的是
     * {@link #findRecommendCultures}（同分类优先 + 热门兜底，
     * 调用点见 {@code ApiHomeController}）。</p>
     *
     * <p>也就是说：原先那套 Apache Mahout 协同过滤<b>从来没有真正对外生效过</b>，
     * 移除 Mahout 对线上行为零影响。此方法连同
     * {@link RecommendService} 的 SQL 实现一并保留并修正，
     * 供将来真要启用「基于行为的个性化推荐」时直接使用；
     * 若要启用，注意本方法的缓存是进程内的（多实例不一致），
     * 且返回的是缓存内部 List（调用方不应修改）。</p>
     */
    @Override
    public List<Culture> getUserTjCulture(String username, Long cultureId) {
        User user = userMapper.findUserByUserName(username);
        if (user == null) {
            return new ArrayList<>();
        }
        Long uid = user.getId();

        // 缓存命中直接返回（同一用户 10 分钟内不重复计算）
        RecommendCacheEntry cached = recommendCache.get(uid);
        if (cached != null && !cached.expired()) {
            return cached.items;
        }

        List<Culture> cultures = recommendService.getRecommendItemsByUser(uid, 4);
        if (cultures == null) {
            cultures = new ArrayList<>();
        }
        if (cultures.size() < 4) {
            cultures = getFullRecommendList(cultures, cultureId);
        }
        recommendCache.put(uid, new RecommendCacheEntry(cultures));
        return cultures;
    }

    //判断是否已经收藏了culture
    @Override
    public boolean isExistScCulture(Long userid, Long bid) {
        Culture culture = cultureMapper.queryExistScCulture(userid, bid);
        return culture != null;
    }

    //收藏culture（收藏变化时清掉该用户推荐缓存）
    @Override
    public void scCulture(Long userid, Long bid) {
        recommendCache.remove(userid);
        cultureMapper.scCultureByUser(userid, bid);
    }

    //我的收藏（前台个人中心收藏卡片：A3 改用缩略图）
    @Override
    public List<Culture> findMyScCulture(Long userid) {
        List<Culture> list = cultureMapper.findMyScCulture(userid);
        thumbnailService.applyCoverThumb(list);
        return list;
    }

    //取消收藏（同样清缓存）
    @Override
    public void cancelScCulture(Long userid, Long bid) {
        recommendCache.remove(userid);
        cultureMapper.deleteScCulture(userid, bid);
    }

    @Override
    public Long queryTotalViewNum() {
        return cultureMapper.queryTotalViewNum();
    }

    @Override
    public Long queryTotalCultureNum() {
        return cultureMapper.queryTotalCultureNum();
    }

    @Override
    public Long queryTotalUserRegNum() {
        return cultureMapper.queryTotalUserRegNum();
    }

    @Override
    public Long queryTotalScNum() {
        return cultureMapper.queryTotalScNum();
    }

    /**
     * 全站搜索（文化）——命中总数（不带组合筛选，等价于四个筛选参数全为 null）。
     *
     * <p>保留这个既有签名给老调用方使用，行为与改造前逐字一致。</p>
     */
    @Override
    public Long querySearchTotal(String keyword) {
        return querySearchTotal(keyword, null, null, null, null);
    }

    /**
     * 全站搜索（文化）——命中总数。
     *
     * <p>检索策略（增量）：关键词 &gt;= 2 个字先走 ngram 全文索引
     * （MATCH(name,description,content) AGAINST('"关键词"' IN BOOLEAN MODE)）；
     * 索引缺失（ERROR 1191）或任何 SQL 异常都在这里被吞掉并降级回 LIKE，
     * 保证搜索接口永远不 500。降级后 5 分钟内不再重试（见 SearchUtil 熔断说明）。</p>
     *
     * <p>【增量】组合筛选（分类 / 标签 / 时间范围）：四个参数都可为 null（= 不启用），
     * 全部为 null 时生成的 SQL 与改造前逐字等价。筛选条件写在 Mapper 的
     * {@code SEARCH_FILTER_SQL} 片段里，全文路径与 LIKE 路径<b>传的是同一组参数</b>，
     * 所以无论走哪条路径、总数与列表的筛选口径都一致（不会出现总数与列表打架）。</p>
     *
     * @param categoryId 分类 id，null = 不限
     * @param tagId      标签 id，null = 不限（exists 子查询，见 Mapper 注释）
     * @param startTime  已规范化的开始时间（{@code yyyy-MM-dd HH:mm:ss}），null = 不限
     * @param endTime    已规范化的结束时间（{@code yyyy-MM-dd HH:mm:ss}），null = 不限
     */
    @Override
    public Long querySearchTotal(String keyword, Long categoryId, Long tagId,
                                 String startTime, String endTime) {
        String kw = SearchUtil.normalizeKeyword(keyword);
        if (kw.isEmpty()) {
            // 与原 LIKE 行为一致：空关键词命中 0（`like ''` 匹配不到任何行）
            return 0L;
        }
        if (SearchUtil.supportsFullText(kw) && SearchUtil.isFullTextUsable()) {
            try {
                return cultureMapper.querySearchTotalByFullText(SearchUtil.toBooleanPhrase(kw),
                        categoryId, tagId, startTime, endTime);
            } catch (Exception e) {
                // 全文索引不存在 / SQL 报错：降级，不影响接口
                SearchUtil.markFullTextUnavailable("CultureService.querySearchTotal", e);
            }
        }
        return cultureMapper.querySearchTotal(SearchUtil.toLikePattern(kw),
                categoryId, tagId, startTime, endTime);
    }

    /**
     * 全站搜索（文化）——分页数据（不带组合筛选，等价于四个筛选参数全为 null）。
     *
     * <p>保留这个既有签名给老调用方使用，行为与改造前逐字一致。</p>
     */
    @Override
    public List<Culture> querySearchData(String keyword, Integer offset, Integer pageSize) {
        return querySearchData(keyword, offset, pageSize, null, null, null, null);
    }

    /**
     * 全站搜索（文化）——分页数据。
     *
     * <p>与 {@link #querySearchTotal(String, Long, Long, String, String)} 同一套策略；
     * 两条查询共用 Mapper 里同一段筛选片段，所以总数与列表一致。
     * 全文与 LIKE 两种方式的返回字段、过滤条件、排序参数完全相同，
     * 前端无感（分页/排序参数不需要变化）。</p>
     *
     * <p>走全文路径时如果 SQL 抛异常（索引缺失等），会自动降级回 LIKE 路径，
     * 降级时<b>同样带上这四个筛选参数</b>，所以筛选结果不会因为降级而变样。</p>
     */
    @Override
    public List<Culture> querySearchData(String keyword, Integer offset, Integer pageSize,
                                         Long categoryId, Long tagId, String startTime, String endTime) {
        String kw = SearchUtil.normalizeKeyword(keyword);
        if (kw.isEmpty()) {
            return Collections.emptyList();
        }
        List<Culture> list = null;
        if (SearchUtil.supportsFullText(kw) && SearchUtil.isFullTextUsable()) {
            try {
                list = cultureMapper.querySearchDataByFullText(SearchUtil.toBooleanPhrase(kw),
                        SearchUtil.toLikePattern(kw), offset, pageSize,
                        categoryId, tagId, startTime, endTime);
            } catch (Exception e) {
                SearchUtil.markFullTextUnavailable("CultureService.querySearchData", e);
                list = null;
            }
        }
        if (list == null) {
            list = cultureMapper.querySearchData(SearchUtil.toLikePattern(kw), offset, pageSize,
                    categoryId, tagId, startTime, endTime);
        }
        // A3：搜索结果卡片同样走缩略图
        thumbnailService.applyCoverThumb(list);
        return list;
    }

    // ===================== SEO（sitemap / rss / meta） =====================
    @Override
    public List<Culture> findSeoList() {
        return cultureMapper.findSeoList();
    }

    @Override
    public List<Culture> findSeoLatest(int limit) {
        // limit 兜底：<=0 时给 1，避免生成 "limit 0" 或负数的非法 SQL
        return cultureMapper.findSeoLatest(limit <= 0 ? 1 : limit);
    }

    @Override
    public Culture findSeoDetail(Long id) {
        if (id == null) return null;
        return cultureMapper.findSeoDetail(id);
    }

    //推荐不满4个 补足4个（热门兜底）
    public List<Culture> getFullRecommendList(List<Culture> tjList, Long cultureId) {
        if (tjList == null) {
            tjList = new ArrayList<>();
        }
        List<Culture> topCultures = cultureMapper.findTop4Culture();
        for (Culture culture : topCultures) {
            // 必须用 Objects.equals 而不是 != ：
            // Long 是包装类型，`!=` 比较的是引用。自增主键超过 127 后不再命中 Long 缓存，
            // `culture.getId() != cultureId` 会恒为 true —— 当前文章会被推荐给它自己。
            if (isExistCulture(tjList, culture.getId()) && tjList.size() < 4
                    && !java.util.Objects.equals(culture.getId(), cultureId)) {
                tjList.add(culture);
            }
        }
        return tjList;
    }

    //是否重复：true=列表中不存在该 id（可用于补足去重）
    public static boolean isExistCulture(List<Culture> cultures, Long bid) {
        for (Culture culture : cultures) {
            // 同样的问题：`==` 对超过 127 的 Long 恒为 false，
            // 会让去重失效、推荐位出现重复条目。
            if (java.util.Objects.equals(culture.getId(), bid)) {
                return false;
            }
        }
        return true;
    }

    // ===================== 后台批量操作 / CSV 导出（增量追加） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Long> ids) {
        List<Long> safeIds = normalizeIds(ids);
        // 逻辑删除：只把 deleted 置 1，前台/列表/统计立即不可见（与单条 deleteCulture 一致）
        return cultureMapper.batchDelete(safeIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchUpdateCategory(List<Long> ids, Long categoryId) {
        List<Long> safeIds = normalizeIds(ids);
        if (categoryId == null) {
            throw new BusinessException("请选择目标分类");
        }
        if (!categoryExists(categoryId)) {
            // 关键：分类不存在时给业务错误而不是让外键报错 → 500
            throw new BusinessException("分类不存在或已被删除");
        }
        return cultureMapper.batchUpdateCategory(safeIds, categoryId);
    }

    @Override
    public List<Map<String, Object>> queryExportBatch(CultureQuery query, int offset, int pageSize) {
        CultureQuery q = query == null ? new CultureQuery() : query;
        // 与后台列表一致的清洗：关键词去空白；categoryId=-1 表示全部
        if (q.getCultureName() != null) {
            String kw = q.getCultureName().trim();
            q.setCultureName(kw.isEmpty() ? null : kw);
        }
        if (q.getCategoryId() != null && q.getCategoryId().longValue() == -1L) {
            q.setCategoryId(null);
        }
        int size = pageSize <= 0 ? MAX_BATCH_SIZE : pageSize;
        return cultureMapper.queryExportBatch(q, offset < 0 ? 0 : offset, size);
    }

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /**
     * 恢复单条文化。
     * 语义与删除严格对称：删除只置 deleted=1，恢复只把 deleted 改回 0；
     * 正文/封面/分类/标签关联等字段都不动（标签关联删除时是物理删除，无法还原，见 TagMapper 注释）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restore(Long id) {
        if (id == null) {
            throw new BusinessException("参数错误：缺少文化 id");
        }
        // SQL 自带 deleted=1 条件：只恢复「已逻辑删除」的行，返回实际影响行数
        return cultureMapper.restoreById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restoreBatch(List<Long> ids) {
        List<Long> safeIds = normalizeIds(ids);
        return cultureMapper.restoreByIds(safeIds);
    }

    /** 分类是否存在（queryAll 只返回未逻辑删除的分类，因此已删除分类视为不存在） */
    private boolean categoryExists(Long categoryId) {
        List<Category> categories = categoryService.queryAll();
        if (categories == null) {
            return false;
        }
        for (Category c : categories) {
            if (categoryId.equals(c.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 批量 id 规整：去 null、去重，并校验「非空 + 不超过 500 条」。
     * 校验失败抛 BusinessException，由控制层转成 400 业务错误。
     */
    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请至少选择一条记录");
        }
        List<Long> safeIds = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || safeIds.contains(id)) continue;
            safeIds.add(id);
        }
        if (safeIds.isEmpty()) {
            throw new BusinessException("请至少选择一条记录");
        }
        if (safeIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException("一次最多操作 " + MAX_BATCH_SIZE + " 条");
        }
        return safeIds;
    }
}
