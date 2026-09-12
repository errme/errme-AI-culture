package com.culture.service;

import com.culture.entity.Culture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * Sitemap 生成服务：负责「sitemap 索引（sitemapindex）+ 分片（urlset）」的 XML 拼装，
 * 让 {@code SeoController} 只保留路由与响应头（缓存/Content-Type）职责。
 *
 * <pre>
 * /sitemap.xml                    → sitemapindex（静态页分片 + 内容分片）
 * /sitemap.xml?shard=static       → 静态页 urlset
 * /sitemap.xml?shard=culture-1    → 第 1 个内容分片 urlset
 * /sitemap-static.xml             → 同上（规范路径形式，见下）
 * /sitemap-culture-{n}.xml        → 同上（规范路径形式，见下）
 * </pre>
 *
 * <h3>为什么分片地址有两种形式</h3>
 * <p>规范形式是 {@code /sitemap-static.xml}、{@code /sitemap-culture-{n}.xml}，但项目现状下这两个
 * 路径<b>爬虫拿不到</b>：</p>
 * <ol>
 *   <li>{@code WebSecurityConfig} 只把 {@code /sitemap.xml}、{@code /robots.txt}、{@code /rss.xml}
 *       放进 permitAll，其余 {@code anyRequest().authenticated()} → 匿名请求得到 401 JSON；</li>
 *   <li>Nginx（{@code deploy/nginx.conf.example}、{@code web/nginx.conf.example}）只对
 *       {@code location = /sitemap.xml} 做反代，新根路径会落到 SPA 的 try_files → 返回 front.html
 *       （HTTP 200 的 HTML，Google 会报「Sitemap 是 HTML」）。</li>
 * </ol>
 * <p>这两个文件不在本次改造范围内，因此默认用 {@code /sitemap.xml?shard=xxx}（复用已被放行且已被反代的
 * 同一个路径）作为索引里的分片地址 —— 今天就能被爬虫抓到，且 sitemap 索引允许 loc 带查询串。
 * 等安全/网关配置补齐后，把 {@code app.site.sitemap.shard-url-mode} 设为 {@code path} 即可切成规范路径，
 * 代码无需再改（两种形式的处理逻辑本就在同一个分片分发方法里）。</p>
 *
 * <h3>内容过滤口径</h3>
 * <p>只输出前台可见内容（{@code deleted=0 AND status=1}）。过滤主要落在 SQL：
 * {@code CultureMapper.findSeoList} 当前是 {@code where c.deleted = 0 and c.status = 1}。
 * 这里额外做一次「若实体带出 status 且不等于 1 则丢弃」的防御 —— 该判断在 SeoCultureMap 不映射
 * status 列时是空操作，一旦查询回归/被改动（例如误删 status 条件后有人补映射），它能挡住草稿泄漏。</p>
 *
 * <h3>失败降级</h3>
 * <p>任何查询异常都在本类内部吞掉并记日志：索引退化为「只含静态页分片」，内容分片退化为空 urlset，
 * 都不会把 500 抛给爬虫（抓取失败会直接影响收录）。</p>
 */
@Service
public class SitemapService {

    /**
     * 每个分片最多包含的 URL 数。
     * sitemap 协议上限是 50000 条 / 50MB，这里取 1000：单文件更小（爬虫单次抓取更快、出错时影响面更小），
     * 且当前站点只有几十条内容，正常永远只有 1 个内容分片，未来扩容到几万条也不用改代码。
     */
    public static final int SHARD_SIZE = 1000;

    /** 静态页分片的 shard key */
    public static final String SHARD_STATIC = "static";

    /** 内容分片的 shard key 前缀，完整形式为 culture-1、culture-2 … */
    public static final String SHARD_CULTURE_PREFIX = "culture-";

    /** XML 声明（所有 sitemap 文件共用） */
    private static final String XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    /** urlset 根标签（带 sitemap 0.9 命名空间） */
    private static final String URLSET_OPEN = "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n";

    /** 静态页（path, changefreq, priority）—— 与前端路由一一对应 */
    private static final String[][] STATIC_PAGES = {
            {"/", "daily", "1.0"},          // 首页：站点门面，权重最高
            {"/culture", "daily", "0.9"},   // 栏目页（文化列表）：内容聚合页，更新频繁
            {"/sentence", "weekly", "0.7"}, // 句子页：更新频率中等
            {"/about", "monthly", "0.5"}    // 关于我：几乎不变
    };

    @Autowired
    private CultureService cultureService;

    /**
     * sitemap 索引：静态页分片 + 内容分片（每 {@link #SHARD_SIZE} 条一个）。
     *
     * @param base     站点绝对地址（末尾无斜杠）
     * @param pathMode true 用 {@code /sitemap-culture-1.xml} 形式；false 用 {@code /sitemap.xml?shard=culture-1}
     * @return sitemapindex XML
     */
    public String buildIndexXml(String base, boolean pathMode) {
        Snapshot snapshot = load();
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd");

        StringBuilder xml = new StringBuilder(1024);
        xml.append(XML_DECL);
        xml.append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        // 静态页分片永远在索引里：它不依赖数据库，查询失败时也能保证索引与收录入口可用
        appendSitemapEntry(xml, shardUrl(base, SHARD_STATIC, pathMode), snapshot.latest, dayFmt);

        // 内容分片：查询失败或没有任何已发布内容时，分片数为 0（索引只剩静态页分片）
        int shards = shardCount(snapshot.cultures.size());
        for (int n = 1; n <= shards; n++) {
            appendSitemapEntry(xml, shardUrl(base, SHARD_CULTURE_PREFIX + n, pathMode),
                    latestOfShard(snapshot.cultures, n), dayFmt);
        }

        xml.append("</sitemapindex>\n");
        return xml.toString();
    }

    /**
     * 按 shard key 输出一个分片 urlset。
     *
     * <p>越界 / 无法识别的 shard key（如 {@code culture-99}、{@code culture-0}、乱填的值）返回
     * <b>HTTP 200 + 空 urlset</b>，而不是 404，理由：</p>
     * <ol>
     *   <li>索引本身有 10 分钟缓存，内容条数在缓存窗口内可能跨过 1000 的边界，此时旧索引里的分片
     *       会短暂「不存在」，404 会让 Search Console 记录抓取错误，空 urlset 则是静默的；
     *   <li>空 urlset 是合法 XML，任何解析器都能处理；404 还需要爬虫区分「暂时没有」与「路径写错」。</li>
     * </ol>
     *
     * @param base     站点绝对地址（末尾无斜杠）
     * @param shardKey {@code static} 或 {@code culture-{n}}
     * @return urlset XML
     */
    public String buildShardXml(String base, String shardKey) {
        String key = shardKey == null ? "" : shardKey.trim();
        if (SHARD_STATIC.equalsIgnoreCase(key)) {
            return buildStaticShardXml(base);
        }
        if (key.regionMatches(true, 0, SHARD_CULTURE_PREFIX, 0, SHARD_CULTURE_PREFIX.length())) {
            long shardNo = parseShardNo(key.substring(SHARD_CULTURE_PREFIX.length()));
            if (shardNo >= 1) {
                return buildCultureShardXml(base, shardNo);
            }
        }
        return emptyUrlset();
    }

    // ==================================================================================
    // 分片内容
    // ==================================================================================

    /** 静态页分片：4 个固定页面；首页与栏目页的 lastmod 取「最近一次内容更新时间」 */
    private String buildStaticShardXml(String base) {
        Snapshot snapshot = load();
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd");

        StringBuilder xml = new StringBuilder(1024);
        xml.append(XML_DECL).append(URLSET_OPEN);
        for (String[] page : STATIC_PAGES) {
            boolean contentDriven = "/".equals(page[0]) || "/culture".equals(page[0]);
            xml.append("  <url>\n");
            appendLoc(xml, base + page[0]);
            if (contentDriven && snapshot.latest != null) {
                xml.append("    <lastmod>").append(dayFmt.format(snapshot.latest)).append("</lastmod>\n");
            }
            xml.append("    <changefreq>").append(page[1]).append("</changefreq>\n");
            xml.append("    <priority>").append(page[2]).append("</priority>\n");
            xml.append("  </url>\n");
        }
        xml.append("</urlset>\n");
        return xml.toString();
    }

    /**
     * 第 shardNo 个内容分片（从 1 开始）：每片最多 {@link #SHARD_SIZE} 条，只输出 id 与 lastmod。
     * 越界（含 shardNo &lt; 1）返回空 urlset。
     */
    private String buildCultureShardXml(String base, long shardNo) {
        Snapshot snapshot = load();
        List<Culture> cultures = snapshot.cultures;
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd");

        // 用 long 计算下标：避免 (shardNo - 1) * SHARD_SIZE 在超大分片号下溢出 int
        long from = (shardNo - 1) * (long) SHARD_SIZE;

        StringBuilder xml = new StringBuilder(4096);
        xml.append(XML_DECL).append(URLSET_OPEN);
        if (from < cultures.size()) {
            long to = Math.min(from + SHARD_SIZE, cultures.size());
            for (int i = (int) from; i < (int) to; i++) {
                Culture culture = cultures.get(i);
                xml.append("  <url>\n");
                appendLoc(xml, base + "/culture/" + culture.getId());
                // lastmod：用更新时间；缺失则整行省略而不是输出空标签
                if (culture.getUpdateTime() != null) {
                    xml.append("    <lastmod>").append(dayFmt.format(culture.getUpdateTime())).append("</lastmod>\n");
                }
                xml.append("    <changefreq>monthly</changefreq>\n");
                xml.append("    <priority>0.8</priority>\n");
                xml.append("  </url>\n");
            }
        }
        xml.append("</urlset>\n");
        return xml.toString();
    }

    /** 空 urlset（越界分片 / 无内容时的合法最小响应） */
    private String emptyUrlset() {
        return XML_DECL + URLSET_OPEN + "</urlset>\n";
    }

    // ==================================================================================
    // 数据加载
    // ==================================================================================

    /** 一次查询的结果快照 */
    private static final class Snapshot {
        /** 已发布内容（按 id 升序），查询失败时为空列表（不会为 null） */
        final List<Culture> cultures;
        /** 全部内容里最近的一次更新时间；没有则为 null（索引/静态页会省略 lastmod） */
        final Date latest;

        Snapshot(List<Culture> cultures, Date latest) {
            this.cultures = cultures;
            this.latest = latest;
        }
    }

    /**
     * 加载内容列表并做可见性兜底过滤 + 稳定排序。
     *
     * <p>排序刻意用 <b>id 升序</b>（SQL 是 id 倒序）：这样新增内容只会追加进最后一个分片，
     * 已有分片的 URL 集合保持稳定，避免新内容插入导致所有分片内容整体漂移、爬虫反复重抓。</p>
     */
    private Snapshot load() {
        List<Culture> raw = null;
        try {
            raw = cultureService.findSeoList();
        } catch (Exception e) {
            // 数据库异常不阻断 sitemap：退化为静态页分片 / 空分片
            System.err.println("[SitemapService] 查询文化列表失败，sitemap 退化为静态页分片：" + e.getMessage());
        }

        List<Culture> cultures = new ArrayList<Culture>();
        Date latest = null;
        if (raw != null) {
            for (Culture culture : raw) {
                if (culture == null || culture.getId() == null) continue;
                // 防御性可见性过滤：SQL 若未过滤 status（回归/被改动）时再挡一次；
                // 当前 SeoCultureMap 不映射 status 列，因此这里通常是空操作（口径详见类注释）
                if (culture.getStatus() != null && culture.getStatus().intValue() != 1) continue;
                cultures.add(culture);
                Date updated = culture.getUpdateTime();
                if (updated != null && (latest == null || updated.after(latest))) {
                    latest = updated;
                }
            }
            Collections.sort(cultures, new Comparator<Culture>() {
                @Override
                public int compare(Culture a, Culture b) {
                    return a.getId().compareTo(b.getId());
                }
            });
        }
        return new Snapshot(cultures, latest);
    }

    // ==================================================================================
    // 小工具
    // ==================================================================================

    /** 分片总数：向上取整；0 条内容时为 0 片 */
    private int shardCount(int total) {
        if (total <= 0) return 0;
        return (total + SHARD_SIZE - 1) / SHARD_SIZE;
    }

    /** 第 n 片里最近的一次更新时间（索引条目的 lastmod）；整片都没有更新时间时返回 null */
    private Date latestOfShard(List<Culture> cultures, int shardNo) {
        int from = (shardNo - 1) * SHARD_SIZE;
        int to = Math.min(from + SHARD_SIZE, cultures.size());
        Date latest = null;
        for (int i = from; i < to; i++) {
            Date updated = cultures.get(i).getUpdateTime();
            if (updated != null && (latest == null || updated.after(latest))) {
                latest = updated;
            }
        }
        return latest;
    }

    /**
     * 分片地址：
     * <ul>
     *   <li>pathMode=true：{@code {base}/sitemap-static.xml}、{@code {base}/sitemap-culture-1.xml}</li>
     *   <li>pathMode=false（默认）：{@code {base}/sitemap.xml?shard=static}、{@code {base}/sitemap.xml?shard=culture-1}</li>
     * </ul>
     * 两种形式都指向本服务的同一个分片处理器。
     */
    private String shardUrl(String base, String shardKey, boolean pathMode) {
        return pathMode ? base + "/sitemap-" + shardKey + ".xml" : base + "/sitemap.xml?shard=" + shardKey;
    }

    /** 索引里的一个 &lt;sitemap&gt; 条目 */
    private void appendSitemapEntry(StringBuilder xml, String loc, Date lastmod, SimpleDateFormat dayFmt) {
        xml.append("  <sitemap>\n");
        xml.append("    <loc>").append(escapeXml(loc)).append("</loc>\n");
        if (lastmod != null) {
            xml.append("    <lastmod>").append(dayFmt.format(lastmod)).append("</lastmod>\n");
        }
        xml.append("  </sitemap>\n");
    }

    /** urlset 里的 &lt;loc&gt; 行（统一在这里做 XML 转义） */
    private void appendLoc(StringBuilder xml, String loc) {
        xml.append("    <loc>").append(escapeXml(loc)).append("</loc>\n");
    }

    /**
     * 把分片号字符串转成正数：只接受纯数字（最多 9 位，足够覆盖 10 亿个分片，同时天然防溢出），
     * 其它情况（空、非数字、0、负数、超长）一律返回 -1 表示「无法识别/越界」。
     */
    private long parseShardNo(String value) {
        if (value == null || value.isEmpty() || value.length() > 9) return -1L;
        long result = 0L;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') return -1L;
            result = result * 10L + (ch - '0');
        }
        return result <= 0L ? -1L : result;
    }

    /**
     * XML 文本转义（URL 与正文里可能带 &amp; &lt; &gt; " '）。
     * 与 SeoController 里的同名私有方法保持一致；两个类各自使用，避免为一个 6 行工具类新建文件。
     */
    private String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
