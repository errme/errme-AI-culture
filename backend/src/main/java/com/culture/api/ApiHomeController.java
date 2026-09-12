package com.culture.api;

import com.culture.entity.Announcement;
import com.culture.entity.Category;
import com.culture.entity.Culture;
import com.culture.entity.Sentence;
import com.culture.query.CultureQuery;
import com.culture.service.*;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 前台公开 API（Vue 前端使用，无需登录）。
 * 说明：原 NavController / CultureIndexController 的页面渲染逻辑，
 * 这里提供 JSON 版本，页面渲染交给 Vue。
 */
@RestController
@RequestMapping("/api")
public class ApiHomeController {

    /** biz_culture.status：1 = 已发布（0=草稿/下架、2=定时待发布）。前台所有入口只展示已发布内容。 */
    private static final int PUBLISHED_STATUS = 1;

    @Autowired
    private CultureService cultureService;
    @Autowired
    private CategoryService categoryService;
    @Autowired
    private AnnouncementService announcementService;
    @Autowired
    private SentenceService sentenceService;
    @Autowired
    private com.culture.service.TagService tagService;
    /** 前台分类列表的 Redis 短缓存（写路径主动失效 + TTL 兜底，Redis 异常自动降级查库） */
    @Autowired
    private com.culture.service.CacheService cacheService;

    /**
     * 首页聚合数据：公告、句子、热门文化、今日文化（原 NavController.index 逻辑）。
     *
     * <p>走 Redis 短缓存（key = {@link CacheService#KEY_HOME}，TTL 30 秒）：
     * 这个接口一次要跑 4 组查询，而「公告/句子」是编辑类内容，发布后希望立刻可见 ——
     * 因此公告与句子的增删改**会主动失效**（见 AnnouncementServiceImpl / SentenceServiceImpl）；
     * 「热门文化/今日文化」属于持续变化的数据（浏览量一直在动），不做逐写失效、交给 30 秒 TTL 兜底，
     * 换来的是首页不再被这 4 组查询拖着走。</p>
     */
    @GetMapping("/home")
    public ApiResult<Map<String, Object>> home() {
        return ApiResult.ok(cacheService.getOrLoadHome(this::buildHome, HOME_TYPE));
    }

    /** /api/home 的返回值类型（Jackson 反序列化用；用 TypeReference 保留 Map/List 泛型） */
    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> HOME_TYPE =
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            };

    private Map<String, Object> buildHome() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("announcements", announcementService.queryAll());
        data.put("sentences", sentenceService.queryAll());
        data.put("hotCultures", cultureService.queryHotCulture());
        data.put("cultureToday", pickCultureOfToday());
        return data;
    }

    /**
     * 今日文化：id % 日期 == 0 优先，否则取第一条（与原逻辑一致）。
     * 前台入口：候选来自 findAll（SQL 已带 status=1），详情再用 findPublishedDetailById 兜底，
     * 保证草稿 / 已下架 / 定时未到点的内容不会出现在首页。
     */
    private Culture pickCultureOfToday() {
        List<Culture> list = cultureService.findAll();
        if (list == null || list.isEmpty()) return null;
        SimpleDateFormat fmt = new SimpleDateFormat("dd");
        int currentTime = Integer.parseInt(fmt.format(new Date()));
        for (Culture c : list) {
            if (c.getId() % currentTime == 0) return cultureService.findPublishedDetailById(c.getId());
        }
        return cultureService.findPublishedDetailById(list.get(0).getId());
    }

    /**
     * 文化列表（分页 + 名称/分类筛选）。
     *
     * <p><b>前台可见性</b>：这里强制 {@code status=1}（已发布）。
     * CultureQuery.status 是绑定字段，必须在请求参数绑定<b>之后</b>覆盖，
     * 这样即使请求里带 {@code ?status=0} 也无法把草稿/下架内容捞出来。
     * SQL 侧复用共用的 whereSql：只有 status != null 时才拼 {@code and u.status = #{status}}，
     * 因此后台入口 /api/admin/culture/list（不设置该值）行为逐字不变，?status=0 仍是草稿箱。</p>
     */
    @GetMapping("/culture/list")
    public ApiResult<PageList> cultureList(CultureQuery query) {
        query.normalizePaging();
        // 前台只展示已发布内容（放在参数绑定之后覆盖，防止被请求参数绕过）
        query.setStatus(PUBLISHED_STATUS);
        return ApiResult.ok(cultureService.listpage(query));
    }

    /**
     * 文化详情（浏览量 +1）。
     *
     * <p><b>前台可见性</b>：只查已发布内容（findPublishedDetailById，SQL 带 status=1），
     * 草稿 / 已下架 / 定时未到点 → 404「内容不存在」，且不累加浏览量。
     * 后台详情 GET /api/admin/culture/detail 仍走共享的 findDetailById，照旧能看到草稿。</p>
     */
    @GetMapping("/culture/detail")
    public ApiResult<Map<String, Object>> cultureDetail(@RequestParam Long id) {
        Culture culture = cultureService.findPublishedDetailById(id);
        if (culture == null) return ApiResult.error(404, "内容不存在");
        cultureService.updateViewNum(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("culture", culture);
        // 详情页「猜你喜欢」：同分类优先，不足用热门补齐（前端可直接渲染）
        data.put("recommendCultures", cultureService.findRecommendCultures(culture, 4));
        // 内容标签（Batch4）：前台展示为可点击 chips，跳转 /tag/{id}
        data.put("tags", tagService.tagsOfCulture(id));
        return ApiResult.ok(data);
    }

    /**
     * 全部分类（前台）。
     *
     * <p>走 Redis 短缓存（key = {@link CacheService#KEY_CATEGORY_LIST}）：这个接口每个前台页面
     * 的筛选下拉都会请求，内容与登录用户无关、写极少，缓存收益高。
     * 分类的新增/编辑/删除/恢复都会主动删 key（见 CategoryServiceImpl），
     * 所以正常操作下前台是**即时**的；TTL 只兜底（配置见 application.yml 的 app.cache.*）。</p>
     */
    @GetMapping("/culture/categorys")
    public ApiResult<List<?>> categorys() {
        return ApiResult.ok(cacheService.getOrLoad(
                CacheService.KEY_CATEGORY_LIST,
                () -> categoryService.queryAll(),
                new com.fasterxml.jackson.core.type.TypeReference<List<Category>>() {
                }));
    }

    /** 句子列表（前台展示） */
    @GetMapping("/sentence/list")
    public ApiResult<List<Sentence>> sentenceList() {
        return ApiResult.ok(sentenceService.findAll());
    }
}
