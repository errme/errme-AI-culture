package com.culture.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * 全站搜索关键词处理工具（文化 / 句子检索共用）。
 *
 * 1. normalizeKeyword：去首尾空白 + 截断到 50 字，避免超长 LIKE 拖慢查询；
 * 2. escapeLike：转义 MySQL LIKE 通配符（% 与 _）以及转义符本身，
 *    与 Mapper 中的 `like #{kw} escape '!'` 配套使用。
 *    若不转义，用户输入一个 "%" 就会变成通配符导致全表命中（注入式通配）。
 * 3. 【增量】ngram 全文检索辅助：长度判断、BOOLEAN MODE 短语拼装，以及
 *    全文索引不可用时的进程级熔断（供 Service 优雅降级回 LIKE，见 {@link #markFullTextUnavailable}）；
 * 4. 【增量】搜索结果高亮：先整体 HTML 转义、再把命中的关键词包进 &lt;em&gt;，防止 XSS。
 * 5. 【增量】组合筛选参数解析：分类/标签 id（{@link #parseOptionalId}）与时间范围
 *    （{@link #normalizeStartTime} / {@link #normalizeEndTime}）；解析失败一律返回 null
 *    （调用方按「忽略该筛选条件」处理，接口不会 500）。
 */
public class SearchUtil {

    /** 关键词长度上限（超出直接截断，不报错） */
    public static final int MAX_KEYWORD_LENGTH = 50;

    /**
     * LIKE 转义符。
     * MySQL 默认转义符是反斜杠，但反斜杠在 Java 字符串 / SQL 字面量里都要二次转义、极易写错，
     * 这里显式选用 '!' 并在 SQL 里声明 `escape '!'`。
     */
    public static final char LIKE_ESCAPE_CHAR = '!';

    private SearchUtil() {
    }

    /** 去空白 + 截断；null 安全。返回空串表示「无关键词」（调用方应直接返回空结果） */
    public static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        String kw = keyword.trim();
        if (kw.length() > MAX_KEYWORD_LENGTH) {
            kw = kw.substring(0, MAX_KEYWORD_LENGTH);
        }
        return kw;
    }

    /** 转义 LIKE 通配符（% / _）与转义符本身；可重复调用（幂等） */
    public static String escapeLike(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(keyword.length() + 8);
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == LIKE_ESCAPE_CHAR || c == '%' || c == '_') {
                sb.append(LIKE_ESCAPE_CHAR);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 一步到位：先 normalize 再 escape（**不带 % 通配**，供需要自定义两侧结构的场景） */
    public static String prepareLikePattern(String keyword) {
        return escapeLike(normalizeKeyword(keyword));
    }

    /**
     * 生成可直接用于 `like #{kw} escape '!'` 的完整模式：`%已转义关键词%`。
     * 注意：Mapper 里写的是 `like #{kw}`，参数必须是带 % 的完整模式，
     * 只传裸词会退化成精确匹配（此处曾因此导致搜索永远 0 命中）。
     */
    public static String toLikePattern(String keyword) {
        String kw = escapeLike(normalizeKeyword(keyword));
        return kw.isEmpty() ? "" : "%" + kw + "%";
    }

    // =====================================================================
    // 组合筛选参数解析（增量：分类 / 标签 / 时间范围）
    // =====================================================================

    /** 时间筛选支持的第一种写法：纯日期（按「一整天」理解，边界见 normalizeStartTime / normalizeEndTime） */
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    /** 时间筛选支持的第二种写法：日期 + 时间（精确到秒，与 created_at 的存库精度一致） */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 解析成功后统一按这个格式回填给 SQL（同时也是库里 created_at 的字符串写法） */
    private static final DateTimeFormatter SQL_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);

    /**
     * 严格模式解析器：{@code 2021-02-30} 这种「格式对但日期不存在」的值判为非法。
     * 默认的 SMART 模式会把它悄悄改成 2021-02-28，那样用户看到的结果就与填入的条件不符。
     * 用 uuuu（proleptic year）而不是 yyyy（year-of-era）是因为 STRICT + yyyy 需要同时给 era。
     */
    private static final DateTimeFormatter STRICT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter STRICT_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    /**
     * 解析可选的 id 参数（categoryId / tagId）。
     *
     * <p>为什么是「String 进、Long 出」而不是直接让 Spring 绑定 Long：
     * 直接声明 {@code @RequestParam Long} 时，{@code ?categoryId=abc} 或 {@code ?categoryId=}
     * 会在参数绑定阶段抛异常（默认 400 / 部分场景 500），而本接口的既有风格是「非法值一律降级」，
     * 且筛选条件的语义是「缩小结果集」，忽略一个坏参数返回全集比整页报错更安全。
     * 因此这里自己解析，失败返回 null（= 不启用该筛选）。</p>
     *
     * @return 解析出的 id；null / 空白 / 非数字都返回 null
     */
    public static Long parseOptionalId(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            // 非数字（含小数、超 Long 范围）：当作没传这个筛选条件
            return null;
        }
    }

    /**
     * 解析时间范围起点：{@code yyyy-MM-dd} 或 {@code yyyy-MM-dd HH:mm:ss}。
     *
     * <p>只给日期时按<b>当天 00:00:00</b> 处理（闭区间左端）。</p>
     *
     * @return 规范化成 {@code yyyy-MM-dd HH:mm:ss} 的字符串；null / 空白 / 格式错 / 日期不存在都返回 null
     */
    public static String normalizeStartTime(String raw) {
        LocalDateTime time = parseFilterTime(raw, false);
        return time == null ? null : time.format(SQL_DATE_TIME_FORMATTER);
    }

    /**
     * 解析时间范围终点：{@code yyyy-MM-dd} 或 {@code yyyy-MM-dd HH:mm:ss}。
     *
     * <p><b>只给日期时按当天 23:59:59 处理</b>（闭区间右端），
     * 语义是「包含 endTime 这一天」：否则同一天的 startTime 与 endTime
     * 会因为 created_at 都带时分秒而永远命中 0 条。</p>
     *
     * <p>为什么回填成字符串而不是 {@code java.util.Date}：MySQL 对「DATETIME 列 与 字符串」
     * 的比较规则是把字符串按字面解析成 DATETIME（见 MySQL 比较规则），不做时区换算；
     * 而绑定 Date 会经过 JDBC 的 serverTimezone 转换，可能整体偏移 8 小时。
     * 走字符串后 SQL 侧永远不会出现跨时区歧义。</p>
     *
     * @return 规范化成 {@code yyyy-MM-dd HH:mm:ss} 的字符串；null / 空白 / 格式错 / 日期不存在都返回 null
     */
    public static String normalizeEndTime(String raw) {
        LocalDateTime time = parseFilterTime(raw, true);
        return time == null ? null : time.format(SQL_DATE_TIME_FORMATTER);
    }

    /**
     * 时间筛选值的公共解析：只接受 {@link #DATE_PATTERN}（按天，末刻由 endOfDay 决定）
     * 与 {@link #DATE_TIME_PATTERN}（精确到秒）两种写法，其余一律判非法（返回 null）。
     */
    private static LocalDateTime parseFilterTime(String raw, boolean endOfDay) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            if (text.length() == DATE_PATTERN.length()) {
                LocalDate date = LocalDate.parse(text, STRICT_DATE_FORMATTER);
                return endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay();
            }
            if (text.length() == DATE_TIME_PATTERN.length()) {
                return LocalDateTime.parse(text, STRICT_DATE_TIME_FORMATTER);
            }
        } catch (DateTimeParseException e) {
            // 格式不符 / 日期不存在：当作没传这个筛选条件
            return null;
        }
        return null;
    }

    // =====================================================================
    // ngram 全文检索辅助（增量）
    // =====================================================================

    /**
     * 全文检索的最小关键词长度。
     * ngram_token_size=2 时按相邻 2 个字符切词，单个汉字切不出任何 token，
     * 实测 BOOLEAN MODE（裸词 / 短语）与 NATURAL LANGUAGE MODE 命中都是 0 条，
     * 所以 1 个字的关键词必须直接走 LIKE，不能走全文检索。
     */
    public static final int MIN_FULLTEXT_LENGTH = 2;

    /** 高亮片段最大长度：只在这个长度内做命中标注，避免响应体被长正文撑大 */
    public static final int HIGHLIGHT_MAX_LENGTH = 200;

    /** 高亮标签（前端需要按 HTML 渲染，片段里其余字符都已转义） */
    public static final String HIGHLIGHT_OPEN = "<em>";
    public static final String HIGHLIGHT_CLOSE = "</em>";

    /** 全文检索失败后的熔断时长：期间直接走 LIKE，避免每次搜索都白跑一条必然失败的 SQL */
    public static final long FULLTEXT_RETRY_INTERVAL_MS = 5 * 60 * 1000L;

    /**
     * BOOLEAN MODE 里有特殊含义的字符：出现在关键词里会破坏短语结构
     * （+ - 要求包含/排除、~ 降权、&lt; &gt; 调权重、* 通配、@ 距离、括号分组、引号定界、反斜杠转义）。
     */
    private static final String BOOLEAN_MODE_SPECIAL_CHARS = "\"+-~<>()*@\\'";

    private static final Logger log = LoggerFactory.getLogger(SearchUtil.class);

    /**
     * 全文检索熔断截止时间（毫秒时间戳），0 表示可用。
     * 进程级、尽力而为的开关：多实例部署时各自熔断，不影响正确性（只是各实例各试一次）。
     */
    private static volatile long fullTextDisabledUntil = 0L;

    /**
     * 关键词长度，按 Unicode 码点计数：中文 1 个字算 1，
     * 避免 emoji（UTF-16 里占 2 个 char）被误判成「2 个字」而去走全文检索。
     */
    public static int lengthInCodePoints(String keyword) {
        String kw = normalizeKeyword(keyword);
        return kw.isEmpty() ? 0 : kw.codePointCount(0, kw.length());
    }

    /**
     * 取可用于全文检索的「裸词」：去掉 BOOLEAN MODE 特殊字符，再去掉首尾空白。
     * 返回空串表示这个关键词不适合走全文检索（调用方应回退 LIKE）。
     */
    public static String fullTextWord(String keyword) {
        String kw = normalizeKeyword(keyword);
        if (kw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(kw.length());
        for (int i = 0; i < kw.length(); i++) {
            char c = kw.charAt(i);
            if (BOOLEAN_MODE_SPECIAL_CHARS.indexOf(c) >= 0) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /**
     * 该关键词能否走全文检索：清洗后仍需 &gt;= {@link #MIN_FULLTEXT_LENGTH} 个字符。
     * 形如 "**" 这种全是 BOOLEAN 操作符的关键词会被判为不可用，从而回退 LIKE。
     */
    public static boolean supportsFullText(String keyword) {
        String word = fullTextWord(keyword);
        return !word.isEmpty() && word.codePointCount(0, word.length()) >= MIN_FULLTEXT_LENGTH;
    }

    /**
     * 生成 BOOLEAN MODE 的短语表达式：{@code "关键词"}。
     *
     * <p>为什么必须加引号：ngram 下裸关键词是「token 或」语义，会误命中只含部分 token 的记录
     * （实测关键词 博物馆 不带引号会命中「故宫博物院」）；加引号后要求 token 相邻，
     * 等价于 {@code LIKE '%关键词%'}（实测两者命中集合完全一致）。</p>
     *
     * @return 形如 {@code "博物馆"}；关键词不可用时返回空串（调用方应改用 LIKE）
     */
    public static String toBooleanPhrase(String keyword) {
        String word = fullTextWord(keyword);
        return word.isEmpty() ? "" : "\"" + word + "\"";
    }

    /** 全文索引当前是否可用（熔断期内返回 false，调用方直接走 LIKE） */
    public static boolean isFullTextUsable() {
        return System.currentTimeMillis() >= fullTextDisabledUntil;
    }

    /**
     * 标记全文检索不可用（索引缺失或 SQL 报错，例如 ERROR 1191），进入熔断期。
     *
     * <p>只在「可用 → 不可用」的瞬间打一条 warn：搜索是高频接口，
     * 每次都打日志会刷爆磁盘；熔断期结束后会自动再试一次，
     * 索引补建好（执行 docs/sql/10_search.sql）后无需重启即可恢复走全文检索。</p>
     *
     * @param scene 调用位置说明（出现在日志里，便于定位）
     * @param e     原始异常；只记录类型与消息，不打印完整堆栈以免刷日志
     */
    public static void markFullTextUnavailable(String scene, Throwable e) {
        boolean wasUsable = isFullTextUsable();
        fullTextDisabledUntil = System.currentTimeMillis() + FULLTEXT_RETRY_INTERVAL_MS;
        if (wasUsable) {
            log.warn("全文检索不可用，已自动降级为 LIKE 查询（{}ms 后重试）：scene={}, cause={}: {}",
                    FULLTEXT_RETRY_INTERVAL_MS, scene,
                    e == null ? "-" : e.getClass().getSimpleName(),
                    e == null ? "-" : e.getMessage());
        }
    }

    // =====================================================================
    // 搜索结果高亮（增量）
    // =====================================================================

    /**
     * HTML 转义（&amp; &lt; &gt; &quot; &#39;）。
     * 高亮片段里的原始文本必须先转义，再插入 {@code <em>}，
     * 否则文化名称/描述里的 &lt;script&gt; 会被当成标签执行（XSS）。
     */
    public static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 生成高亮片段：截断到 maxLength → HTML 转义 → 把命中的关键词包成 {@code <em>…</em>}。
     *
     * <p>安全说明：转义在前、插标签在后，所以返回的字符串里除了自己加的 em 标签，
     * 不存在任何来自数据的标签；前端用 v-html / innerHTML 渲染是安全的。
     * 关键词本身也先转义再参与匹配，避免关键词里的 &amp; &lt; 把标签截断。</p>
     *
     * @param text      原始文本（可为 null）
     * @param keyword   原始关键词（内部自行 normalize，不做 LIKE/BOOLEAN 转义）
     * @param maxLength 片段最大长度；&lt;=0 时用 {@link #HIGHLIGHT_MAX_LENGTH}
     * @return 可直接放进 HTML 的片段；无命中时就是「截断 + 转义」后的纯文本
     */
    public static String highlight(String text, String keyword, int maxLength) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int limit = maxLength <= 0 ? HIGHLIGHT_MAX_LENGTH : maxLength;
        int cut = limit;
        // 截断点若落在代理对中间（emoji），往前退一个 char，避免产生半个字符
        if (text.length() > cut && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        String snippet = text.length() > cut ? text.substring(0, cut) + "…" : text;
        String escaped = escapeHtml(snippet);

        String kw = normalizeKeyword(keyword);
        if (kw.isEmpty()) {
            return escaped;
        }
        String escKw = escapeHtml(kw);
        if (escKw.isEmpty()) {
            return escaped;
        }

        StringBuilder sb = new StringBuilder(escaped.length() + 16);
        int from = 0;
        int idx;
        while ((idx = indexOfIgnoreCase(escaped, escKw, from)) >= 0) {
            sb.append(escaped, from, idx);
            sb.append(HIGHLIGHT_OPEN).append(escaped, idx, idx + escKw.length()).append(HIGHLIGHT_CLOSE);
            from = idx + escKw.length();
        }
        sb.append(escaped, from, escaped.length());
        return sb.toString();
    }

    /** 大小写不敏感的 indexOf（Java 8 的 String 没有 ignoreCase 版本），from 为起始下标 */
    private static int indexOfIgnoreCase(String source, String target, int from) {
        int max = source.length() - target.length();
        for (int i = Math.max(from, 0); i <= max; i++) {
            if (source.regionMatches(true, i, target, 0, target.length())) {
                return i;
            }
        }
        return -1;
    }
}
