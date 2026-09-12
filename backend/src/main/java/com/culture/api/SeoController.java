package com.culture.api;

import com.culture.entity.Culture;
import com.culture.service.CultureService;
import com.culture.service.SitemapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SEO 端点：站点地图、爬虫规则、RSS 与页面 meta 信息接口。
 *
 * <pre>
 * GET /sitemap.xml                       sitemap 索引（sitemapindex）：静态页分片 + 内容分片
 * GET /sitemap.xml?shard=static          静态页分片（urlset，默认分片地址形式）
 * GET /sitemap.xml?shard=culture-1       第 1 个内容分片（urlset，每片最多 1000 条 URL）
 * GET /sitemap-static.xml                静态页分片（规范路径形式，见设计说明 7）
 * GET /sitemap-culture-{n}.xml           内容分片（规范路径形式，见设计说明 7）
 * GET /robots.txt                        爬虫规则 + Sitemap 声明
 * GET /rss.xml                           RSS 2.0（最新 20 条文化）
 * GET /api/seo/meta?path=/culture/123    页面 meta（OG / canonical / JSON-LD）
 * GET /api/seo/meta?id=123               同上，直接按内容 id 查（两种写法等价）
 * </pre>
 *
 * 设计说明：
 *  1. 前端是 Vue3 SPA，页面由 Nginx 静态托管，爬虫抓不到前端路由背后的内容；
 *     这里把「可索引的 URL 清单」交给搜索引擎，配合前端每页的 title/description/canonical 使用。
 *  2. 站点绝对地址统一取配置项 app.site.base-url（默认 http://localhost:8080），
 *     不从 HttpServletRequest 推断 —— 反向代理/预渲染环境下 request 的 host 并不可靠。
 *  3. 端点都必须免登录（见 WebSecurityConfig 的 permitAll 列表 + JwtAuthFilter.isPublic()），
 *     否则爬虫只能拿到 401；这条对下面第 7 点的分片路径形式尤其关键。
 *  4. 关于 produces：项目里 CorsConfig#configureContentNegotiation 把全局内容协商固定成了
 *     application/json 且 ignoreAcceptHeader=true。ProducesRequestCondition 也是走这个
 *     ContentNegotiationManager 取「可接受类型」的，因此只要在 @GetMapping 上写
 *     produces = "application/xml;charset=UTF-8"，映射阶段就会判定为不可接受而直接 406
 *     （请求还没进方法体）。所以这里**不写 produces**，改为在方法返回的 ResponseEntity 上
 *     显式设置 Content-Type（application/xml;charset=UTF-8 / text/plain;charset=UTF-8）：
 *     预设了 Content-Type 后 Spring 会跳过内容协商，效果与 produces 完全一致，
 *     且不会影响 /api/** 的 JSON 输出。
 *  5. 容错：任何一次数据库查询失败都只记日志并退化成「最小可用内容」（索引只含静态页分片 /
 *     空 urlset / 空条目 rss / 兜底 meta），绝不抛 500 —— 搜索引擎抓取失败会直接影响收录。
 *  6. 缓存：统一 10 分钟（Cache-Control: public, max-age=600），减少爬虫高频抓取对库的压力。
 *  7. 分片地址有两种形式（同一套处理器）：
 *     - 规范路径：/sitemap-static.xml、/sitemap-culture-{n}.xml；
 *     - 查询串（默认）：/sitemap.xml?shard=static、/sitemap.xml?shard=culture-{n}。
 *     默认用查询串是因为规范路径目前**爬虫拿不到**：WebSecurityConfig 只 permitAll 了
 *     /sitemap.xml、/robots.txt、/rss.xml（其余 anyRequest().authenticated() → 匿名 401），
 *     Nginx 也只对 location = /sitemap.xml 做反代（新根路径会落进 SPA try_files，返回
 *     front.html 的 200 HTML）。这两个文件不在本次改造范围，所以索引里的 {@code <loc>} 先指向
 *     已被放行/反代的 /sitemap.xml?shard=…；等安全与网关配置补齐后，把配置项
 *     app.site.sitemap.shard-url-mode 设为 path 即可切成规范路径，Java 代码不用再动。
 *     sitemap 索引的 {@code <loc>} 允许带查询串，Google/Bing 都会照常抓取。
 */
@RestController
public class SeoController {

    /** 站点对外访问地址（末尾斜杠会被去掉，避免拼出 //culture） */
    @Value("${app.site.base-url:http://localhost:8080}")
    private String baseUrl;

    /** 站点名（RSS 频道标题 / JSON-LD publisher / meta 标题后缀） */
    @Value("${app.site.name:遇你}")
    private String siteName;

    /** 站点描述（RSS 频道描述 / 兜底 meta description） */
    @Value("${app.site.description:遇你 · 传统文化 —— 传统文化图文记录与分享}")
    private String siteDescription;

    /**
     * sitemap 索引里分片地址的形式：
     * <ul>
     *   <li>{@code query}（默认）：{@code /sitemap.xml?shard=culture-1} —— 复用已放行且已反代的路径；</li>
     *   <li>{@code path}：{@code /sitemap-culture-1.xml} —— 规范形式，需先放行安全/网关（见类注释第 7 点）。</li>
     * </ul>
     * 给默认值即可工作，不必改 application.yml（那个文件不在本次改造范围内）。
     */
    @Value("${app.site.sitemap.shard-url-mode:query}")
    private String shardUrlMode;

    @Autowired
    private CultureService cultureService;

    /** sitemap 索引/分片的 XML 生成（详见 SitemapService） */
    @Autowired
    private SitemapService sitemapService;

    /** 响应缓存秒数：10 分钟（爬虫抓取频率有限，内容更新延迟 10 分钟可接受） */
    private static final long CACHE_SECONDS = 600L;

    /** RSS 条目数：最新 20 条 */
    private static final int RSS_ITEM_LIMIT = 20;

    /** meta description 截断长度（约等于搜索结果摘要展示长度） */
    private static final int META_DESC_MAX = 120;

    /** RSS 摘要截断长度 */
    private static final int RSS_DESC_MAX = 150;

    /** 默认分享图（首页等没有封面的页面用，避免 og:image 为空） */
    private static final String DEFAULT_IMAGE = "/index/images/banner_1.png";

    /** JSON-LD publisher.logo 用图 */
    private static final String LOGO_IMAGE = "/index/images/logo.png";

    /** RSS 生成器标识（{@code <generator>} 是自由文本，给出可追溯的服务端来源） */
    private static final String RSS_GENERATOR = "culture-backend SeoController";

    /** 静态页的标题与描述（文化详情页由数据库字段生成，其它未知路径走兜底） */
    private static final Map<String, String[]> STATIC_META = new LinkedHashMap<String, String[]>();

    static {
        STATIC_META.put("/culture", new String[]{
                "倾一世", "传统文化内容合集：按分类浏览全部图文记录，含地址、简介与热度。"});
        STATIC_META.put("/sentence", new String[]{
                "琴弦上", "短句与文案摘录，随手记下的句子合集。"});
        STATIC_META.put("/about", new String[]{
                "关于我", "关于本站：记录传统文化的图文站点，以及联系方式。"});
    }

    /** /culture/{id} 路径解析 */
    private static final Pattern CULTURE_PATH = Pattern.compile("^/culture/(\\d+)$");

    /** 富文本块（script/style 连内容一起去掉） */
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");

    /** 任意 HTML 标签 */
    private static final Pattern HTML_TAG = Pattern.compile("(?s)<[^>]*>");

    /** 连续空白（含 &nbsp; 产生的 \u00a0） */
    private static final Pattern BLANKS = Pattern.compile("[\\s\\u00a0]+");

    // ==================================================================================
    // 1) sitemap：索引 + 分片
    // ==================================================================================

    /**
     * sitemap.xml —— <b>sitemapindex</b>（统一输出索引，不再直接输出 urlset）。
     *
     * <p>索引里始终有静态页分片（不依赖数据库），内容按每 {@link SitemapService#SHARD_SIZE} 条
     * 一个分片列出；当前内容只有几十条，因此正常只有 1 个内容分片，扩容后不必改代码。</p>
     *
     * <p>带 {@code ?shard=} 参数时本方法改为输出对应分片的 urlset（见下方带 {@code ?shard=} 参数时的分片分发逻辑）：
     * {@code /sitemap.xml?shard=static}、{@code /sitemap.xml?shard=culture-1}。之所以复用同一个
     * 路径，是因为新根路径目前没有被安全/网关放行（详见类注释第 7 点）。</p>
     */
    @GetMapping("/sitemap.xml")
    public ResponseEntity<String> sitemap(HttpServletResponse response,
                                          @RequestParam(value = "shard", required = false) String shard) {
        applyCacheHeader(response);
        String base = siteBaseUrl();
        if (shard == null || shard.trim().isEmpty()) {
            // 索引：<loc> 全部是绝对地址，指向下面的分片端点
            return xmlResponse(sitemapService.buildIndexXml(base, isPathShardMode()));
        }
        return xmlResponse(sitemapService.buildShardXml(base, shard));
    }

    /**
     * 静态页分片的规范路径形式 {@code GET /sitemap-static.xml}。
     *
     * <p>需要 WebSecurityConfig 的 permitAll 与 Nginx location 同步放行后才能被爬虫访问，
     * 否则匿名请求是 401、Nginx 侧是 SPA 的 front.html（见类注释第 7 点）。
     * 内容与 {@code /sitemap.xml?shard=static} 完全一致。</p>
     */
    @GetMapping("/sitemap-static.xml")
    public ResponseEntity<String> sitemapStatic(HttpServletResponse response) {
        applyCacheHeader(response);
        return xmlResponse(sitemapService.buildShardXml(siteBaseUrl(), SitemapService.SHARD_STATIC));
    }

    /**
     * 内容分片的规范路径形式 {@code GET /sitemap-culture-{n}.xml}（n 从 1 开始）。
     *
     * <p>路径变量故意用 {@code [0-9]+} 限定并声明成 String：非数字路径不会进入本方法，
     * 也不会出现「类型转换失败 → 400」。越界分片（如 culture-99）返回 200 + 空 urlset，
     * 理由见 {@link SitemapService#buildShardXml(String, String)}。</p>
     */
    @GetMapping("/sitemap-culture-{n:[0-9]+}.xml")
    public ResponseEntity<String> sitemapCulture(HttpServletResponse response,
                                                 @PathVariable("n") String n) {
        applyCacheHeader(response);
        return xmlResponse(sitemapService.buildShardXml(siteBaseUrl(),
                SitemapService.SHARD_CULTURE_PREFIX + n));
    }

    /** 分片地址是否使用规范路径形式（配置项 app.site.sitemap.shard-url-mode=path），默认 false */
    private boolean isPathShardMode() {
        return "path".equalsIgnoreCase(shardUrlMode == null ? "" : shardUrlMode.trim());
    }

    // ==================================================================================
    // 2) robots.txt
    // ==================================================================================

    /**
     * robots.txt —— 允许抓取前台，屏蔽后台（/admin 与 /static/admin 两套资源）、
     * 接口（/api）与需登录的个人中心；末尾声明 sitemap 绝对地址（搜索引擎只认绝对 URL）。
     */
    @GetMapping("/robots.txt")
    public ResponseEntity<String> robots(HttpServletResponse response) {
        applyCacheHeader(response);
        String base = siteBaseUrl();
        StringBuilder txt = new StringBuilder(256);
        txt.append("User-agent: *\n");
        txt.append("Allow: /\n");
        // 后台管理：无收录价值，且抓取会消耗登录态
        txt.append("Disallow: /admin\n");
        // 后台静态资源（老 jQuery 后台的皮肤/脚本目录）
        txt.append("Disallow: /static/admin\n");
        // REST 接口：JSON 响应不应进入索引
        txt.append("Disallow: /api\n");
        // 个人中心：需登录的私有页面（前端另有 meta robots=noindex 双保险）
        txt.append("Disallow: /center\n");
        // 抓取间隔：Google 已忽略该指令（它用 Search Console 里的抓取速率），
        // Bing / Yandex 等仍会遵守，对本站这种小站是很有价值的限速保护
        txt.append("Crawl-delay: 1\n");
        txt.append("\n");
        txt.append("Sitemap: ").append(base).append("/sitemap.xml\n");
        return textResponse(txt.toString());
    }

    // ==================================================================================
    // 3) rss.xml
    // ==================================================================================

    /**
     * rss.xml —— RSS 2.0，最新 {@link #RSS_ITEM_LIMIT} 条文化。
     *
     * <p>标题/链接/发布时间（created_at，缺失时退回更新时间）/摘要（desc 优先、否则正文，
     * 去 HTML 标签后截断）都在这里生成；查询失败时输出只有频道信息的空订阅源，不会 500。</p>
     *
     * <p>细节：</p>
     * <ul>
     *   <li>{@code xmlns:atom} + {@code <atom:link rel="self">} —— 让聚合器/校验器知道订阅源的
     *       权威地址（RSS 2.0 本身没有 self 概念，这是 Atom 的补充约定，属主流做法）；</li>
     *   <li>{@code <lastBuildDate>} 取<b>最新一条内容的发布时间</b>（而不是本次响应时间）：
     *       这样内容没变时该字段稳定，聚合器不会每次抓取都误判「订阅源更新了」；</li>
     *   <li>{@code <guid isPermaLink="true">} 保持永久链接形式，聚合器据此去重。</li>
     * </ul>
     */
    @GetMapping("/rss.xml")
    public ResponseEntity<String> rss(HttpServletResponse response) {
        applyCacheHeader(response);
        String base = siteBaseUrl();
        // RFC 822 时间格式（RSS 2.0 pubDate / lastBuildDate 规定格式）
        SimpleDateFormat rfc822 = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);

        // 先收集可用条目并算出最新发布时间：lastBuildDate 在频道头里，必须先算再输出
        List<Culture> items = new ArrayList<Culture>();
        Date latestPub = null;
        try {
            List<Culture> cultures = cultureService.findSeoLatest(RSS_ITEM_LIMIT);
            if (cultures != null) {
                for (Culture culture : cultures) {
                    if (culture == null || culture.getId() == null) continue;
                    // 可见性兜底（口径同 sitemap）：SQL 已过滤 deleted=0 且 status=1，
                    // 这里再挡一次 status 不为 1 的条目（SeoCultureMap 不映射 status 时为空操作）
                    if (culture.getStatus() != null && culture.getStatus().intValue() != 1) continue;
                    items.add(culture);
                    Date pub = culture.getCreateTime() != null
                            ? culture.getCreateTime() : culture.getUpdateTime();
                    if (pub != null && (latestPub == null || pub.after(latestPub))) {
                        latestPub = pub;
                    }
                }
            }
        } catch (Exception e) {
            // 出错时给一个合法的空订阅源，避免阅读器/聚合站拿到 500
            System.err.println("[SeoController] 查询最新文化失败，rss 仅输出频道信息：" + e.getMessage());
        }

        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\">\n");
        xml.append("  <channel>\n");
        xml.append("    <title>").append(escapeXml(siteName)).append("</title>\n");
        xml.append("    <link>").append(escapeXml(base + "/")).append("</link>\n");
        xml.append("    <description>").append(escapeXml(siteDescription)).append("</description>\n");
        // self 链接：订阅源自身的绝对地址（配置项决定，不依赖请求 Host）
        xml.append("    <atom:link rel=\"self\" type=\"application/rss+xml\" href=\"")
                .append(escapeXml(base + "/rss.xml")).append("\"/>\n");
        xml.append("    <language>zh-CN</language>\n");
        // 没有可用条目时（查询失败或站点为空）退回当前时间，保证标签始终存在且合法
        Date buildDate = latestPub != null ? latestPub : new Date();
        xml.append("    <lastBuildDate>").append(rfc822.format(buildDate)).append("</lastBuildDate>\n");
        xml.append("    <generator>").append(escapeXml(RSS_GENERATOR)).append("</generator>\n");

        for (Culture culture : items) {
            String link = base + "/culture/" + culture.getId();
            xml.append("    <item>\n");
            xml.append("      <title>").append(escapeXml(plainText(culture.getCultureName()))).append("</title>\n");
            xml.append("      <link>").append(escapeXml(link)).append("</link>\n");
            // guid 用永久链接，聚合器可据此去重
            xml.append("      <guid isPermaLink=\"true\">").append(escapeXml(link)).append("</guid>\n");
            Date pubDate = culture.getCreateTime() != null ? culture.getCreateTime() : culture.getUpdateTime();
            if (pubDate != null) {
                xml.append("      <pubDate>").append(rfc822.format(pubDate)).append("</pubDate>\n");
            }
            // 摘要：优先 description，为空时退回正文；两者都是富文本，先去掉标签再截断
            String summary = summaryOf(culture, RSS_DESC_MAX);
            xml.append("      <description>").append(escapeXml(summary)).append("</description>\n");
            xml.append("    </item>\n");
        }

        xml.append("  </channel>\n");
        xml.append("</rss>\n");
        return xmlResponse(xml.toString());
    }

    // ==================================================================================
    // 4) /api/seo/meta —— 页面 meta（OG / canonical / JSON-LD）
    // ==================================================================================

    /**
     * 页面 meta 信息接口（前台匿名可访问，供前端 useSeo 与爬虫使用）。
     *
     * <p>两种等价调用方式：</p>
     * <ul>
     *   <li>{@code /api/seo/meta?path=/culture/123} —— 按前端路由 path 查（含静态页）；</li>
     *   <li>{@code /api/seo/meta?id=123} —— 直接按内容 id 查详情页 meta。</li>
     * </ul>
     *
     * <p>文化详情页返回：title / ogTitle / ogDescription / ogImage（封面图绝对 URL）/
     * canonical / keywords / jsonLd（schema.org Article：headline、image、datePublished、
     * dateModified、author、publisher）。静态页返回基础 meta，无需收录的路径（/admin、
     * /center、/search、/auth）返回 noindex=true。</p>
     *
     * <p>内容不存在或查询异常时不返回 500：HTTP 200 + code=404 + 最小可用 meta（noindex），
     * 前端可直接渲染标题，爬虫也不会把错误页当成正常内容收录。</p>
     */
    @GetMapping("/api/seo/meta")
    public ResponseEntity<ApiResult<Map<String, Object>>> meta(
            HttpServletResponse response,
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "id", required = false) Long id) {

        applyCacheHeader(response);
        String cleanPath = normalizePath(path);
        Long cultureId = id != null ? id : parseCultureId(cleanPath);

        Map<String, Object> data;
        if (cultureId != null) {
            Culture culture = null;
            try {
                culture = cultureService.findSeoDetail(cultureId);
            } catch (Exception e) {
                System.err.println("[SeoController] 查询 meta 详情失败，返回最小可用内容：id="
                        + cultureId + "，" + e.getMessage());
            }
            if (culture == null) {
                String notFoundPath = cleanPath.isEmpty() ? "/culture/" + cultureId : cleanPath;
                ApiResult<Map<String, Object>> body = ApiResult.error(404, "内容不存在");
                body.setData(minimalMeta(notFoundPath));
                return cachedJson(body);
            }
            data = cultureMeta(culture);
        } else {
            data = pageMeta(cleanPath);
        }
        return cachedJson(ApiResult.ok(data));
    }

    /** 文化详情页 meta：OG + canonical + Article JSON-LD */
    private Map<String, Object> cultureMeta(Culture culture) {
        String base = siteBaseUrl();
        String name = plainText(culture.getCultureName());
        if (name.isEmpty()) name = siteName;

        String canonical = base + "/culture/" + culture.getId();
        String title = name + " · " + siteName;
        // 摘要：desc 优先，为空退回正文；两者都可能带富文本标签，先清洗再截断
        String description = truncate(stripHtml(firstNonBlank(culture.getDesc(), culture.getInfo())), META_DESC_MAX);
        if (description.isEmpty()) description = siteDescription;

        String image = coverImageUrl(culture.getFmUrl());
        String keywords = name + ",传统文化," + siteName;

        Map<String, Object> data = baseMeta("/culture/" + culture.getId(), "article",
                title, description, keywords, canonical, false, image);
        data.put("jsonLd", articleJsonLd(culture, name, description, image, canonical));
        return data;
    }

    /** schema.org Article 结构化数据（字段覆盖 Google 富结果要求的必填项） */
    private Map<String, Object> articleJsonLd(Culture culture, String name, String description,
                                              String image, String canonical) {
        Map<String, Object> ld = new LinkedHashMap<String, Object>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "Article");
        ld.put("headline", name);
        ld.put("description", description);
        ld.put("image", image);
        // datePublished 用创建时间，dateModified 用更新时间（updated_at 非空，缺失才退回创建时间）
        String published = isoTime(culture.getCreateTime());
        String modified = isoTime(culture.getUpdateTime() != null
                ? culture.getUpdateTime() : culture.getCreateTime());
        if (!published.isEmpty()) ld.put("datePublished", published);
        if (!modified.isEmpty()) ld.put("dateModified", modified);

        Map<String, Object> author = new LinkedHashMap<String, Object>();
        author.put("@type", "Person");
        String authorName = culture.getUser() == null ? null : plainText(culture.getUser().getUsername());
        author.put("name", authorName == null || authorName.isEmpty() ? siteName : authorName);
        ld.put("author", author);

        Map<String, Object> logo = new LinkedHashMap<String, Object>();
        logo.put("@type", "ImageObject");
        logo.put("url", siteBaseUrl() + LOGO_IMAGE);
        Map<String, Object> publisher = new LinkedHashMap<String, Object>();
        publisher.put("@type", "Organization");
        publisher.put("name", siteName);
        publisher.put("logo", logo);
        ld.put("publisher", publisher);

        ld.put("mainEntityOfPage", canonical);
        return ld;
    }

    /** 静态页 / 未知路径的 meta */
    private Map<String, Object> pageMeta(String path) {
        String p = (path == null || path.isEmpty()) ? "/" : path;
        String base = siteBaseUrl();
        String title = siteName;
        String description = siteDescription;

        if (!"/".equals(p)) {
            String[] preset = STATIC_META.get(p);
            if (preset != null) {
                title = preset[0] + " · " + siteName;
                description = preset[1];
            } else {
                title = p + " · " + siteName;
            }
        }

        String canonical = base + ("/".equals(p) ? "/" : p);
        boolean noindex = isPrivatePath(p);
        String image = base + DEFAULT_IMAGE;
        String keywords = "传统文化,文化," + siteName;
        return baseMeta(p, "website", title, description, keywords, canonical, noindex, image);
    }

    /** 最小可用 meta：内容不存在或查询失败时的兜底（noindex，避免错误页被收录） */
    private Map<String, Object> minimalMeta(String path) {
        String p = normalizePath(path);
        if (p.isEmpty()) p = "/";
        return baseMeta(p, "website", siteName, siteDescription, "传统文化,文化," + siteName,
                siteBaseUrl() + p, true, siteBaseUrl() + DEFAULT_IMAGE);
    }

    /**
     * 组装统一的 meta 结构。
     * 同时给出 og* 与通用字段：og* 供 Open Graph 标签直接使用，title/description 供 <title> 使用。
     */
    private Map<String, Object> baseMeta(String path, String type, String title, String description,
                                         String keywords, String canonical, boolean noindex, String image) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("path", path);
        data.put("type", type);
        data.put("title", title);
        data.put("ogTitle", title);
        data.put("description", description);
        data.put("ogDescription", description);
        data.put("keywords", keywords);
        data.put("ogImage", image);
        data.put("image", image);
        data.put("canonical", canonical);
        data.put("ogUrl", canonical);
        data.put("noindex", noindex);
        data.put("robots", noindex ? "noindex,follow" : "index,follow");
        data.put("siteName", siteName);
        return data;
    }

    /** 需要「不被收录」的页面（后台/登录/个人中心/站内搜索） */
    private boolean isPrivatePath(String path) {
        return path.startsWith("/admin")
                || path.startsWith("/auth")
                || path.startsWith("/center")
                || path.startsWith("/search")
                || path.startsWith("/api");
    }

    // ==================================================================================
    // 响应与工具方法
    // ==================================================================================

    /** 带缓存的 JSON 响应（meta 接口用；内容协商默认 JSON，这里不指定 Content-Type） */
    private ResponseEntity<ApiResult<Map<String, Object>>> cachedJson(ApiResult<Map<String, Object>> body) {
        return ResponseEntity.ok().body(body);
    }

    /** sitemap / rss 响应：显式声明 XML + UTF-8，绕过全局「强制 JSON」的内容协商 */
    private ResponseEntity<String> xmlResponse(String body) {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/xml;charset=UTF-8"))
                .body(body);
    }

    /** robots 响应：显式声明 text/plain + UTF-8（爬虫按纯文本解析） */
    private ResponseEntity<String> textResponse(String body) {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/plain;charset=UTF-8"))
                .body(body);
    }

    /**
     * Cache-Control: public, max-age=600。
     *
     * <p>这里刻意用 servlet 原生 setHeader，而不用 ResponseEntity#cacheControl：
     * Spring Security 默认会给所有响应写一条 Cache-Control: no-cache, no-store, max-age=0,
     * must-revalidate（HeaderWriterFilter 在进入 Controller 之前写入），如果这里再 addHeader
     * 一条，响应就会出现两个 Cache-Control 值，缓存会按最严格的 no-store 处理 —— 等于白设。
     * setHeader 会整体替换该响应头，保证最终只有「public, max-age=600」一条。</p>
     */
    private void applyCacheHeader(HttpServletResponse response) {
        response.setHeader("Cache-Control", "public, max-age=" + CACHE_SECONDS);
    }

    /** 统一取站点根地址：去掉末尾斜杠，保证拼接结果规范 */
    private String siteBaseUrl() {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /**
     * 把 path 归一化成站内路径：
     * 允许传完整 URL（只取 path 部分），丢掉查询串/哈希，末尾斜杠去掉（根路径除外）。
     */
    private String normalizePath(String path) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty()) return "";

        int scheme = value.indexOf("://");
        if (scheme > 0) {
            int slash = value.indexOf('/', scheme + 3);
            value = slash < 0 ? "/" : value.substring(slash);
        }
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int hash = value.indexOf('#');
        if (hash >= 0) value = value.substring(0, hash);

        if (value.isEmpty()) return "/";
        if (!value.startsWith("/")) value = "/" + value;
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 从 /culture/{id} 取出内容 id；不是详情页路径时返回 null */
    private Long parseCultureId(String path) {
        if (path == null || path.isEmpty()) return null;
        Matcher matcher = CULTURE_PATH.matcher(path);
        if (!matcher.matches()) return null;
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 封面图绝对 URL：绝对地址原样返回，站内相对路径补站点域名，裸文件名按 /showFmImg/ 规则拼（与前端 coverUrl 一致） */
    private String coverImageUrl(String fmUrl) {
        String base = siteBaseUrl();
        String value = fmUrl == null ? "" : fmUrl.trim();
        if (value.isEmpty()) return base + DEFAULT_IMAGE;
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("/")) return base + value;
        return base + "/showFmImg/" + value;
    }

    /** ISO 8601 时间（JSON-LD 要求），如 2026-09-11T22:59:41+08:00；为空返回空串 */
    private String isoTime(Date date) {
        if (date == null) return "";
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(date);
    }

    /** RSS/meta 摘要：desc 优先，为空退回正文 */
    private String summaryOf(Culture culture, int max) {
        String source = firstNonBlank(culture.getDesc(), culture.getInfo());
        return truncate(stripHtml(source), max);
    }

    /** 取第一个非空白字符串 */
    private String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) return first;
        return second == null ? "" : second;
    }

    /**
     * 富文本转纯文本：先整块去掉 script/style（连内容），再去掉其余标签，
     * 还原常见实体并压缩空白 —— 输出的纯文本再交给 escapeXml 做 XML 转义。
     */
    private String stripHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        String text = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        return BLANKS.matcher(text).replaceAll(" ").trim();
    }

    /** 纯文本净化（标题等短文本）：去掉标签并压缩空白 */
    private String plainText(String value) {
        return stripHtml(value == null ? "" : value);
    }

    /** 超长截断（按字符数，追加省略号） */
    private String truncate(String text, int max) {
        if (text == null) return "";
        String value = text.trim();
        if (max <= 0 || value.length() <= max) return value;
        return value.substring(0, max) + "…";
    }

    /** XML 文本转义：URL 与正文里可能带 & < > " ' 等字符，避免生成非法 XML */
    private String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
