package com.culture.api;

import com.culture.entity.Culture;
import com.culture.entity.Sentence;
import com.culture.service.CultureService;
import com.culture.service.SearchStatService;
import com.culture.service.SentenceService;
import com.culture.util.SearchUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全站搜索 API（前台公开，无需登录；JwtAuthFilter.isPublic 与 WebSecurityConfig 已放行）。
 *
 * <pre>
 * GET /api/search?keyword=xxx&amp;page=1&amp;pageSize=10&amp;type=all|culture|sentence
 *                 [&amp;categoryId=1][&amp;tagId=2][&amp;startTime=2021-01-01][&amp;endTime=2021-12-31]
 * GET /api/search/hot?limit=10            热门搜索词（免登录；等价别名 /api/home/search/hot）
 * GET /api/search/history?limit=10        当前登录用户的搜索历史（需登录）
 * </pre>
 *
 * 返回统一信封 ApiResult，data 结构：
 * { keyword, totalCulture, totalSentence, cultures:[...], sentences:[...] }
 *
 * · 文化：name / description / content 三字段匹配，名称命中优先，其次 view_count desc；
 *   关键词 >= 2 字走 ngram 全文索引（MATCH ... AGAINST('"kw"' IN BOOLEAN MODE)），
 *   1 个字或全文索引不可用时自动降级回 LIKE（见 CultureServiceImpl，异常在 Service 内被吞掉）；
 * · 句子：content（全文）/ create_name（LIKE）匹配，正文命中优先，其次 created_at desc；
 * · 两类结果都用同一个 page / pageSize 分页（各自独立计数）；
 * · 关键词为空 → 直接返回空结果（不报错）；trim + 截断 50 字 + LIKE 通配符转义见 SearchUtil；
 * · 文化结果每条额外带一个 highlight 字段（已 HTML 转义 + &lt;em&gt; 标注的片段，见 {@link #highlightCultures}），
 *   其余字段与旧响应完全一致，前端不改也能跑；
 * · 关键词 >= 2 字时顺手累计热门搜索词、并给登录用户记一条搜索历史，两者失败都不影响搜索本身。
 *
 * <h3>【增量】服务端组合筛选（分类 / 标签 / 时间范围）</h3>
 *
 * <pre>
 * categoryId  Long    分类 id，匹配 biz_culture.category_id；仅作用于文化结果
 * tagId       Long    标签 id，匹配 biz_culture_tag（exists 子查询，不会产生重复行）；仅作用于文化结果
 * startTime   String  创建时间闭区间起点（含）；作用在 created_at
 * endTime     String  创建时间闭区间终点（含）；作用在 created_at
 * </pre>
 *
 * · <b>四个参数全部可选</b>：都不传（或传空串 / 非法值）时，SQL 与返回结果与改造前<b>逐字一致</b>。
 * · 时间格式只认两种：{@code yyyy-MM-dd} 与 {@code yyyy-MM-dd HH:mm:ss}：
 *   - 只给日期的 startTime → 当天 00:00:00；
 *   - 只给日期的 endTime → 当天 <b>23:59:59</b>（语义是「包含 endTime 这一天」，
 *     否则 startTime=endTime=同一天会因为记录都带时分秒而永远命中 0 条）；
 *   - 解析成功后统一重新格式化成 {@code yyyy-MM-dd HH:mm:ss} 再交给 SQL（边界与理由见
 *     {@link SearchUtil#normalizeStartTime} / {@link SearchUtil#normalizeEndTime}）；
 *   - startTime &gt; endTime 时不报错，按「空区间」处理 → 返回 0 条（确定、可预期）。
 * · <b>非法参数一律「忽略该筛选条件」</b>（不返回 400、更不返回 500）：
 *   categoryId/tagId 非数字、超 Long 范围、空串 → 视为不筛选；时间格式错、日期不存在
 *   （如 2021-02-30）→ 视为不筛选。理由：本接口既有的参数容错风格就是「非法值降级」
 *   （type 非法按 all、page&lt;1 按 1、pageSize&gt;50 按 50），且筛选的语义是「收窄结果集」，
 *   忽略一个坏参数返回全集比让整个搜索页报错更安全；同时这里刻意用 String 接参、
 *   自己解析，避免 Spring 在参数绑定阶段因类型不匹配抛异常（那会变成 400/500）。
 * · <b>句子侧</b>：biz_sentence 只有 created_at 可筛（没有分类，也没有标签关联表），
 *   因此分类 / 标签筛选<b>在句子侧被忽略</b>（type=all 时句子仍按关键词返回，
 *   只是不受分类/标签约束），时间范围在句子侧照常生效。前端若要「按分类/标签搜索」
 *   应传 type=culture，这样返回体里就不会出现未受筛选约束的句子。
 * · 高亮逻辑不变：{@link SearchUtil#highlight} 只在筛选后的结果行上照旧生成。
 */
@RestController
@RequestMapping("/api")
public class ApiSearchController {

    /** 默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 10;
    /** 每页条数上限，避免 pageSize 被放大导致全表扫描 */
    private static final int MAX_PAGE_SIZE = 50;
    /** 热门词 / 搜索历史默认条数 */
    private static final int DEFAULT_TOP_LIMIT = 10;
    /** 热门词 / 搜索历史条数上限（与 Redis 历史保留条数、前端下拉条数对齐） */
    private static final int MAX_TOP_LIMIT = 20;

    @Autowired
    private CultureService cultureService;

    @Autowired
    private SentenceService sentenceService;

    @Autowired
    private SearchStatService searchStatService;

    /**
     * Spring MVC 正在使用的同一个 ObjectMapper（JacksonAutoConfiguration 提供）。
     * 用途：把 Culture 转成 Map 再追加 highlight，字段名与日期等格式和直接返回实体完全一致，
     * 这样既不用给实体加非表字段（其它接口的响应不受影响），又保持前端零改动。
     */
    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping("/search")
    public ApiResult<Map<String, Object>> search(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "categoryId", required = false) String categoryId,
            @RequestParam(value = "tagId", required = false) String tagId,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            HttpServletRequest request) {

        String kw = SearchUtil.normalizeKeyword(keyword);

        int safePage = (page == null || page < 1) ? 1 : page;
        int safePageSize = (pageSize == null || pageSize < 1)
                ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safePageSize;

        // 【增量】组合筛选参数规整：非法值一律降级为 null（= 不启用该筛选），接口不会 400/500。
        // 这里刻意用 String 接参再自己解析：若直接声明 Long，?categoryId=abc 会在 Spring 参数绑定
        // 阶段就抛异常；时间同理（格式错必须能走到「忽略」分支，而不是变成 400/500）。
        // 说明：categoryId / tagId 只作用于文化结果，句子侧忽略；时间范围两侧都生效
        //（句子侧用 created_at，见 SentenceService 注释）。
        Long filterCategoryId = SearchUtil.parseOptionalId(categoryId);
        Long filterTagId = SearchUtil.parseOptionalId(tagId);
        String filterStartTime = SearchUtil.normalizeStartTime(startTime);
        String filterEndTime = SearchUtil.normalizeEndTime(endTime);

        // type 只认 culture / sentence，其余（含 all、非法值、空）都按「全部」处理
        boolean wantCulture = !"sentence".equalsIgnoreCase(type);
        boolean wantSentence = !"culture".equalsIgnoreCase(type);

        long totalCulture = 0L;
        long totalSentence = 0L;
        List<Culture> cultures = Collections.emptyList();
        List<Sentence> sentences = Collections.emptyList();

        if (!kw.isEmpty()) {
            // 热门搜索词 / 搜索历史：只要关键词 >= 2 个字（与全文检索门槛一致）就记录，
            // 内部已 try/catch，任何存储失败都只是丢一条统计，不影响下面的搜索结果。
            if (SearchUtil.lengthInCodePoints(kw) >= SearchUtil.MIN_FULLTEXT_LENGTH) {
                searchStatService.recordKeyword(kw);
                Object loginUserId = request.getAttribute(JwtAuthFilter.ATTR_LOGIN_USER_ID);
                if (loginUserId instanceof Long) {
                    searchStatService.recordHistory((Long) loginUserId, kw);
                }
            }

            if (wantCulture) {
                // 四个筛选参数全为 null 时，这两次调用与 org 版本返回结果完全一致（同一段 SQL）
                Long total = cultureService.querySearchTotal(kw, filterCategoryId, filterTagId,
                        filterStartTime, filterEndTime);
                totalCulture = total == null ? 0L : total;
                cultures = cultureService.querySearchData(kw, offset, safePageSize,
                        filterCategoryId, filterTagId, filterStartTime, filterEndTime);
            }
            if (wantSentence) {
                // 句子侧无分类 / 标签字段：只传时间范围，另两个筛选在这里被有意忽略
                Long total = sentenceService.querySearchTotal(kw, filterStartTime, filterEndTime);
                totalSentence = total == null ? 0L : total;
                sentences = sentenceService.querySearchData(kw, offset, safePageSize,
                        filterStartTime, filterEndTime);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keyword", kw);
        data.put("totalCulture", totalCulture);
        data.put("totalSentence", totalSentence);
        data.put("cultures", highlightCultures(cultures, kw));
        data.put("sentences", sentences);
        return ApiResult.ok(data);
    }

    /**
     * 热门搜索词（免登录公开）。
     *
     * <pre>GET /api/search/hot?limit=10  →  data: [{ keyword, searchCount }, ...]</pre>
     *
     * · limit 默认 10、上限 20；按 search_count desc, last_search_at desc 取前 N；
     * · 表为空、表还没建（未执行 docs/sql/10_search.sql）或查询异常时都返回空数组，不报错；
     * · 关于第二个路径：{@code /api/search/hot} 已被 JwtAuthFilter.isPublic 放行（startsWith("/api/search")），
     *   但 WebSecurityConfig 的 permitAll 白名单里登记的是精确路径 "/api/search"，
     *   匿名请求 /api/search/hot 仍会在 Spring Security 层被拦成 401（已在本机实测确认）。
     *   因此这里额外暴露等价的 {@code /api/home/search/hot}（/api/home/** 已在白名单里），
     *   前端可直接用它；等 WebSecurityConfig 补上 "/api/search/hot" 后两个地址都可用。
     */
    @GetMapping({"/search/hot", "/home/search/hot"})
    public ApiResult<List<Map<String, Object>>> hot(
            @RequestParam(value = "limit", required = false) Integer limit) {
        int safeLimit = (limit == null || limit < 1) ? DEFAULT_TOP_LIMIT : Math.min(limit, MAX_TOP_LIMIT);
        List<Map<String, Object>> list = searchStatService.findHotKeywords(safeLimit);
        return ApiResult.ok(list == null ? Collections.<Map<String, Object>>emptyList() : list);
    }

    /**
     * 当前登录用户的搜索历史（需登录）。
     *
     * <pre>GET /api/search/history?limit=10  →  data: ["关键词", ...]（最新在前）</pre>
     *
     * · 数据来自 Redis List（key = search:history:{userId}，最近 20 条、去重）；
     * · 未登录：/api/search/history 不在 WebSecurityConfig 的 permitAll 白名单里，
     *   现有 Security 逻辑会直接返回 HTTP 401 {"code":401,...}（JwtAuthFilter 只把它当公开前缀放行，
     *   真正的 401 由 Spring Security 的 anyRequest().authenticated() 给出）；
     *   下面这层判断是兜底，防止将来白名单被改成 /api/search/** 后泄露他人搜索历史；
     * · Redis 不可用 / 无历史时返回空数组，不报错。
     */
    @GetMapping("/search/history")
    public ApiResult<List<String>> history(
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        Object loginUserId = request.getAttribute(JwtAuthFilter.ATTR_LOGIN_USER_ID);
        if (!(loginUserId instanceof Long)) {
            return ApiResult.error(401, "请先登录后再查看搜索历史");
        }
        int safeLimit = (limit == null || limit < 1) ? DEFAULT_TOP_LIMIT : Math.min(limit, MAX_TOP_LIMIT);
        List<String> list = searchStatService.findHistory((Long) loginUserId, safeLimit);
        return ApiResult.ok(list == null ? Collections.<String>emptyList() : list);
    }

    // =====================================================================
    // 搜索结果高亮（内部工具）
    // =====================================================================

    /**
     * 给文化搜索结果逐条补 {@code highlight} 字段。
     *
     * <p>做法：先用 Spring 的 ObjectMapper 把 Culture 转成 Map（字段与格式和原响应完全一致），
     * 再追加 highlight —— 不给实体加非表字段，所以其它接口的 JSON 一个字段都不会多。</p>
     *
     * <p>highlight 取值规则：名称命中 → 高亮后的名称；描述命中 → 高亮后的描述摘要；
     * 两者都命中则「名称 · 描述」；都没命中（只命中正文）则给一段「转义 + 截断」的描述摘要。
     * 片段里除自己插入的 {@code <em>} 外全部 HTML 转义，前端可安全用 v-html 渲染。</p>
     */
    private List<Map<String, Object>> highlightCultures(List<Culture> cultures, String keyword) {
        if (cultures == null || cultures.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> items = new ArrayList<>(cultures.size());
        for (Culture culture : cultures) {
            if (culture == null) {
                continue;
            }
            Map<String, Object> item = toItemMap(culture);
            item.put("highlight", buildHighlight(culture, keyword));
            items.add(item);
        }
        return items;
    }

    /** Culture → Map（字段与直接返回实体时 Jackson 的输出一致），转换失败时退化成只带 id 的 Map */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toItemMap(Culture culture) {
        try {
            return (Map<String, Object>) objectMapper.convertValue(culture, LinkedHashMap.class);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("id", culture.getId());
            return fallback;
        }
    }

    /** 生成一条结果的 highlight 片段（详情见 {@link #highlightCultures}） */
    private String buildHighlight(Culture culture, String keyword) {
        String nameHighlight = SearchUtil.highlight(culture.getCultureName(), keyword,
                SearchUtil.HIGHLIGHT_MAX_LENGTH);
        String descHighlight = SearchUtil.highlight(culture.getDesc(), keyword,
                SearchUtil.HIGHLIGHT_MAX_LENGTH);
        boolean nameHit = nameHighlight.contains(SearchUtil.HIGHLIGHT_OPEN);
        boolean descHit = descHighlight.contains(SearchUtil.HIGHLIGHT_OPEN);
        if (nameHit && descHit) {
            return nameHighlight + " · " + descHighlight;
        }
        if (nameHit) {
            return nameHighlight;
        }
        // descHit 或「只命中正文」：都返回描述摘要（后者不含 em，纯转义文本）
        return descHighlight;
    }
}
