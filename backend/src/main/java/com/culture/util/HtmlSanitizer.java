package com.culture.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 白名单清洗器（零依赖实现）。
 *
 * <p>用途：把不可信 / 半可信的 HTML 在<b>入库前</b>清洗成可安全 {@code v-html} 输出的片段，
 * 防止存储型 XSS。提供两种策略：</p>
 *
 * <table border="1">
 *   <tr><th>策略</th><th>方法</th><th>适用内容</th><th>放行的能力</th></tr>
 *   <tr>
 *     <td>严格</td><td>{@link #sanitize(String)}</td>
 *     <td>评论等普通 UGC</td>
 *     <td>仅基础排版（p/br/strong/em/ul/ol/li/a/h1-h6/blockquote/code/pre）</td>
 *   </tr>
 *   <tr>
 *     <td>富文本</td><td>{@link #sanitizeRichText(String, int)}</td>
 *     <td>后台文化正文（Quill 编辑器产出）</td>
 *     <td>在严格模式基础上增加 img / table / span / div / section / hr / 视频 iframe、
 *         以及受校验的 class 与 style</td>
 *   </tr>
 * </table>
 *
 * <p><b>两种策略共同的安全底线：</b></p>
 * <ul>
 *   <li>{@code script/style/object/embed/svg/math/form/...} 等危险标签连同内部文本一起丢弃；</li>
 *   <li>凡是以 {@code on} 开头的事件属性（{@code onclick}/{@code onerror}/…）、
 *       {@code srcdoc}/{@code formaction}/{@code xlink:href} 一律丢弃；</li>
 *   <li>URL 属性（{@code href}/{@code src}）只接受 {@code http(s)://}、站内 {@code /}、
 *       锚点 {@code #}、{@code mailto:}，其余（{@code javascript:}/{@code data:}/{@code vbscript:}）拒绝；</li>
 *   <li>文本与属性值统一实体转义，避免拼接出新的标签或属性。</li>
 * </ul>
 *
 * <p><b>为什么需要「富文本」这一档（背景）：</b>
 * 原实现的注释写着「后台管理员录入的正文属于可信内容，不走这里」，但项目带有
 * <b>按钮级权限的 RBAC</b>——内容编辑权限可以下放给非管理员角色。一旦下放，
 * 「管理员内容」就不再等于「可信内容」：拥有内容权限的人填入
 * {@code <img src=x onerror=...>} 就会在所有访客（以及真正的管理员）浏览器里执行。
 * 因此正文也必须清洗，只是不能按评论那么严，否则会丢掉图片、表格、对齐等排版。</p>
 *
 * <p>实现方式上，两种策略<b>共用同一套解析器</b>（见 {@link Policy}），
 * 避免为富文本再抄一份解析逻辑而产生行为分叉。</p>
 */
public final class HtmlSanitizer {

    private HtmlSanitizer() { }

    /**
     * 清洗策略：标签集合 + 属性集合 + 属性值校验方式。
     *
     * <p>把策略抽出来的目的：严格模式与富文本模式可以共用同一个解析循环，
     * 只需替换这里的集合，不必复制粘贴解析代码。</p>
     */
    private static final class Policy {
        final Set<String> allowedTags;
        final Set<String> voidTags;
        final Set<String> dropWithContent;
        final Map<String, Set<String>> allowedAttrs;
        /** 需要做 URL 协议校验的属性名（href / src） */
        final Set<String> urlAttrs;
        /** 需要做 class 值校验的属性名 */
        final Set<String> classAttrs;
        /** 需要做 style 值校验的属性名 */
        final Set<String> styleAttrs;

        Policy(Set<String> allowedTags, Set<String> voidTags, Set<String> dropWithContent,
               Map<String, Set<String>> allowedAttrs, Set<String> urlAttrs,
               Set<String> classAttrs, Set<String> styleAttrs) {
            this.allowedTags = allowedTags;
            this.voidTags = voidTags;
            this.dropWithContent = dropWithContent;
            this.allowedAttrs = allowedAttrs;
            this.urlAttrs = urlAttrs;
            this.classAttrs = classAttrs;
            this.styleAttrs = styleAttrs;
        }
    }

    /** 各标签允许保留的属性 */
    private static Map<String, Set<String>> attrs(Object... tagThenAttrs) {
        Map<String, Set<String>> m = new HashMap<>();
        for (int i = 0; i < tagThenAttrs.length; i += 2) {
            String tag = (String) tagThenAttrs[i];
            @SuppressWarnings("unchecked")
            Set<String> set = (Set<String>) tagThenAttrs[i + 1];
            m.put(tag, set);
        }
        return m;
    }

    private static Set<String> set(String... items) {
        return new HashSet<>(Arrays.asList(items));
    }

    /** 连同内部文本一起丢弃的标签（两种策略一致） */
    private static final Set<String> DROP_WITH_CONTENT = set(
            "script", "style", "object", "embed", "template", "noscript",
            "svg", "math", "link", "meta", "base", "form", "input", "button",
            "textarea", "select", "option", "applet", "frame", "frameset", "xml");

    /** 需要做 URL 校验的属性 */
    private static final Set<String> URL_ATTRS = set("href", "src");

    /** ==================== 严格策略（评论等普通 UGC）==================== */
    private static final Policy STRICT = new Policy(
            set("p", "br", "strong", "b", "em", "i", "u", "s", "del", "ins",
                    "blockquote", "code", "pre", "ul", "ol", "li", "a",
                    "h1", "h2", "h3", "h4", "h5", "h6"),
            set("br"),
            DROP_WITH_CONTENT,
            attrs("a", set("href", "title")),
            URL_ATTRS,
            Collections.emptySet(),
            Collections.emptySet());

    /**
     * ==================== 富文本策略（后台文化正文）====================
     *
     * <p>在严格模式基础上，按「数据库里真实存在的内容」补齐能力
     * （已抽样统计过线上正文实际用到的标签与属性）：
     * {@code img}（配图）、{@code class}/{@code style}（Quill 的对齐与字号颜色）、
     * {@code table} 系列（表格）、{@code iframe}（视频嵌入）。</p>
     */
    private static final Policy RICH = new Policy(
            set("p", "br", "strong", "b", "em", "i", "u", "s", "del", "ins",
                    "blockquote", "code", "pre", "ul", "ol", "li", "a",
                    "h1", "h2", "h3", "h4", "h5", "h6",
                    // 富文本额外允许
                    "span", "div", "section", "figure", "figcaption",
                    "img", "hr", "sub", "sup",
                    "table", "thead", "tbody", "tfoot", "tr", "td", "th",
                    "iframe"),
            set("br", "img", "hr"),
            DROP_WITH_CONTENT,
            attrs(
                    "a", set("href", "title", "target", "rel"),
                    "img", set("src", "alt", "title", "width", "height"),
                    "iframe", set("src", "title", "width", "height", "allow", "allowfullscreen", "frameborder"),
                    "td", set("colspan", "rowspan"),
                    "th", set("colspan", "rowspan"),
                    // Quill 用 class 表达对齐（ql-align-center 等）与视频节点（ql-video），
                    // 用 style 表达字号/颜色/缩进。二者都需要放开，但要做值校验。
                    "p", set("class", "style"),
                    "span", set("class", "style"),
                    "div", set("class", "style"),
                    "section", set("class", "style"),
                    "h1", set("class", "style"), "h2", set("class", "style"),
                    "h3", set("class", "style"), "h4", set("class", "style"),
                    "h5", set("class", "style"), "h6", set("class", "style"),
                    "li", set("class", "style"),
                    "blockquote", set("class", "style"),
                    "pre", set("class", "style"),
                    "figure", set("class", "style"),
                    "figcaption", set("class", "style"),
                    "table", set("class", "style"),
                    "td", set("class", "style", "colspan", "rowspan"),
                    "th", set("class", "style", "colspan", "rowspan")),
            URL_ATTRS,
            set("class"),
            set("style"));

    /** class 只允许字母/数字/下划线/连字符/空格（Quill 的 class 全部符合） */
    private static final Pattern SAFE_CLASS = Pattern.compile("^[A-Za-z0-9_\\- ]{1,200}$");

    /**
     * style 里允许出现的 CSS 属性。
     *
     * <p>刻意<b>不含</b> {@code position} —— 否则可用 {@code position:fixed} 做覆盖式
     * UI 伪装（点击劫持）；也不含 {@code behavior}、{@code filter} 等冷门可执行属性。</p>
     */
    private static final Set<String> SAFE_STYLE_PROPS = set(
            "color", "background-color", "background",
            "font-size", "font-family", "font-weight", "font-style",
            "text-align", "text-decoration", "text-indent", "line-height", "letter-spacing",
            "margin", "margin-left", "margin-right", "margin-top", "margin-bottom",
            "padding", "padding-left", "padding-right", "padding-top", "padding-bottom",
            "border", "border-radius", "border-color", "border-width", "border-style",
            "width", "height", "max-width", "min-width", "max-height", "min-height",
            "vertical-align", "white-space", "list-style", "list-style-type", "overflow");

    private static final Set<String> BOOLEAN_ATTRS = set("allowfullscreen");

    private static final String[] URL_PREFIXES = {"http://", "https://", "/", "#", "mailto:"};

    private static final Pattern ENTITY = Pattern.compile("&[a-zA-Z][a-zA-Z0-9]{1,10};|&#\\d{1,7};|&#[xX][0-9a-fA-F]{1,6};");

    /** 清洗（严格策略，默认长度上限 2000 字符） */
    public static String sanitize(String html) {
        return sanitize(html, 2000);
    }

    /** 严格策略清洗：适用于评论等普通 UGC。 */
    public static String sanitize(String html, int maxLen) {
        return doSanitize(html, maxLen, STRICT);
    }

    /** 富文本策略清洗（默认长度上限 200000 字符，正文可能很长）。 */
    public static String sanitizeRichText(String html) {
        return sanitizeRichText(html, 200_000);
    }

    /**
     * 富文本策略清洗：适用于后台文化正文（Quill 产出）。
     *
     * <p>会保留图片、表格、视频嵌入与对齐/字号/颜色，但<b>丢弃全部脚本与事件属性</b>。
     * 超长内容按 maxLen 截断，避免撑爆数据库列。</p>
     */
    public static String sanitizeRichText(String html, int maxLen) {
        return doSanitize(html, maxLen, RICH);
    }

    /** 去掉全部标签，仅保留纯文本（用于昵称、摘要、搜索关键词等场景） */
    public static String toPlainText(String html) {
        if (html == null) return "";
        String noTag = html.replaceAll("(?s)<[^>]*>", "");
        return unescape(noTag).trim();
    }

    /**
     * 清洗单行纯文本字段（如文化的「一句话描述」）。
     *
     * <p>该字段在数据库里是 varchar，但前台用 {@code v-html} 输出，因此同样要清洗：
     * 已经是一个纯文本字段，最简单也最安全的做法就是<b>整段转义</b>，
     * 顺带去掉标签与换行。</p>
     */
    public static String sanitizePlainText(String text, int maxLen) {
        if (text == null) return "";
        // 先把可能的标签整体剥掉（含未闭合的 "<"），再整体转义，防止残留尖括号被当标签解析
        String noTag = text.replaceAll("(?s)<[^>]*>?", "");
        String s = escapeText(noTag).replace("\r", " ").replace("\n", " ");
        s = s.trim();
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    // ==================== 核心解析循环 ====================

    private static String doSanitize(String html, int maxLen, Policy policy) {
        if (html == null) return "";
        // 统一换行，去掉 NUL 等控制字符
        String input = html.replace("\r\n", "\n").replace("\r", "\n").replace("\0", "");
        StringBuilder out = new StringBuilder(Math.min(input.length(), Math.max(maxLen, 0)));
        int i = 0;
        int n = input.length();

        while (i < n) {
            int lt = input.indexOf('<', i);
            if (lt < 0) {
                appendEscaped(out, input.substring(i), maxLen);
                break;
            }
            appendEscaped(out, input.substring(i, lt), maxLen);

            // 注释：直接丢弃
            if (input.startsWith("<!--", lt)) {
                int end = input.indexOf("-->", lt + 4);
                i = end < 0 ? n : end + 3;
                continue;
            }

            int gt = findTagEnd(input, lt);
            if (gt < 0) { // 形如 "a < b" 的裸 '<'
                appendEscaped(out, "&lt;", maxLen);
                i = lt + 1;
                continue;
            }
            String raw = input.substring(lt + 1, gt);
            boolean closing = raw.startsWith("/");
            String body = closing ? raw.substring(1) : raw;
            String tag = readTagName(body);
            i = gt + 1;

            if (tag.isEmpty()) continue;   // 无效标签，丢弃

            if (policy.dropWithContent.contains(tag)) {
                // 连同内部文本一起丢弃（找配对的结束标签）
                i = skipElement(input, i, tag);
                continue;
            }
            if (!policy.allowedTags.contains(tag)) {
                continue;                  // 非白名单标签：只丢标签，保留内部文本
            }
            if (policy.voidTags.contains(tag)) {
                // 自闭合标签也要过属性校验（例如 img 的 src 可能是 javascript:）
                StringBuilder voidOut = new StringBuilder("<").append(tag);
                int written = appendAttrs(voidOut, tag, body.substring(tag.length()), policy);
                voidOut.append('>');
                // 图片没有 src 就没有任何意义（原先会留下一个空的 <img>，
                // 在页面上表现为破图）。清洗掉属性后发现 src 丢失，就整条丢弃。
                if ("img".equals(tag) && written == 0) continue;
                if (out.length() < maxLen) out.append(voidOut);
                continue;
            }
            if (closing) {
                out.append("</").append(tag).append('>');
                continue;
            }

            // 开标签：仅保留白名单属性
            StringBuilder tagOut = new StringBuilder("<").append(tag);
            appendAttrs(tagOut, tag, body.substring(tag.length()), policy);
            tagOut.append('>');
            if (out.length() < maxLen) out.append(tagOut);
            if (out.length() >= maxLen) break;
        }

        String result = out.toString();
        return result.length() > maxLen ? result.substring(0, maxLen) : result;
    }

    /**
     * 按策略过滤并输出属性。
     *
     * @return 实际写入的属性个数（调用方据此判断「图片是否还有 src」这类语义）
     */
    private static int appendAttrs(StringBuilder tagOut, String tag, String attrText, Policy policy) {
        Set<String> allowed = policy.allowedAttrs.get(tag);
        if (allowed == null || allowed.isEmpty()) return 0;

        int written = 0;
        boolean hasRel = false;
        for (String[] attr : parseAttributes(attrText)) {
            String name = attr[0].toLowerCase(Locale.ROOT);
            String value = attr[1];
            if (!allowed.contains(name)) continue;

            // 双保险：即便将来白名单里误加了事件属性，这里也会挡掉
            if (isDangerousAttrName(name)) continue;

            if (policy.urlAttrs.contains(name) && !isSafeUrl(value)) continue;
            if (policy.classAttrs.contains(name) && !isSafeClass(value)) continue;
            if (policy.styleAttrs.contains(name) && !isSafeStyle(value)) continue;

            if ("href".equals(name) && value.startsWith("http")) {
                tagOut.append(" rel=\"nofollow noopener\" target=\"_blank\"");
                hasRel = true;
            }
            if (isBooleanAttr(name)) {
                tagOut.append(' ').append(name);
                written++;
                continue;
            }
            tagOut.append(' ').append(name).append("=\"").append(escapeAttr(value)).append('"');
            written++;
        }
        // a[target=_blank] 未显式带 rel 时补上 noopener，避免反向制表符劫持
        if ("a".equals(tag) && !hasRel && tagOut.indexOf("target=") >= 0 && tagOut.indexOf("rel=") < 0) {
            tagOut.append(" rel=\"noopener\"");
        }
        return written;
    }

    /** 事件属性与其它可执行属性名（无论白名单怎么写，这些一律丢弃） */
    private static boolean isDangerousAttrName(String name) {
        return name.startsWith("on")          // onclick / onerror / onload / ...
                || "srcdoc".equals(name)      // iframe 内联文档
                || "formaction".equals(name)
                || "xlink:href".equals(name)
                || "xmlns".equals(name);
    }

    private static boolean isBooleanAttr(String name) {
        return BOOLEAN_ATTRS.contains(name);
    }

    // ==================== 属性值校验 ====================

    /** class 值校验 */
    private static boolean isSafeClass(String value) {
        return value != null && SAFE_CLASS.matcher(value).matches();
    }

    /**
     * style 值校验：逐条声明检查属性名是否在白名单内，
     * 并拒绝任何可外链 / 可执行的构造。
     */
    private static boolean isSafeStyle(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty() || v.length() > 600) return false;

        String lower = v.toLowerCase(Locale.ROOT);
        // 明确拒绝：外链、IE 表达式、脚本伪协议、CSS 注释（可用来拼接绕过）
        if (lower.contains("url(") || lower.contains("expression")
                || lower.contains("javascript") || lower.contains("@import")
                || lower.contains("\\") || lower.contains("<") || lower.contains(">")
                || lower.contains("/*") || lower.contains("&")) {
            return false;
        }
        for (String decl : v.split(";")) {
            String d = decl.trim();
            if (d.isEmpty()) continue;
            int colon = d.indexOf(':');
            if (colon <= 0) return false;
            String prop = d.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String val = d.substring(colon + 1).trim();
            if (prop.isEmpty() || val.isEmpty()) return false;
            if (!SAFE_STYLE_PROPS.contains(prop)) return false;
        }
        return true;
    }

    private static boolean isSafeUrl(String value) {
        if (value == null) return false;
        // 去掉空白与控制字符，防止 "java\nscript:" 这类绕过
        String v = value.replaceAll("[\\s\\u0000-\\u001F]", "").toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return false;
        for (String prefix : URL_PREFIXES) {
            if (v.startsWith(prefix)) return true;
        }
        return false;
    }

    // ==================== 内部工具 ====================

    /** 找到标签结束的 '>'（跳过引号内的内容） */
    private static int findTagEnd(String s, int from) {
        char quote = 0;
        for (int i = from + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>' || c == '<') {   // '<' 说明这不是一个完整标签
                return c == '>' ? i : -1;
            }
        }
        return -1;
    }

    private static String readTagName(String body) {
        int i = 0;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        StringBuilder sb = new StringBuilder();
        while (i < body.length()) {
            char c = body.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '_') sb.append(c);
            else break;
            i++;
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** 解析属性：返回 [name, value] 数组（值已去引号） */
    private static java.util.List<String[]> parseAttributes(String s) {
        java.util.List<String[]> list = new java.util.ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == '/')) i++;
            int start = i;
            while (i < s.length() && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '=' && s.charAt(i) != '/') i++;
            if (start == i) break;
            String name = s.substring(start, i);
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            String value = "";
            if (i < s.length() && s.charAt(i) == '=') {
                i++;
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
                if (i < s.length() && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
                    char q = s.charAt(i++);
                    int vs = i;
                    while (i < s.length() && s.charAt(i) != q) i++;
                    value = s.substring(vs, Math.min(i, s.length()));
                    if (i < s.length()) i++;
                } else {
                    int vs = i;
                    while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
                    value = s.substring(vs, i);
                }
            }
            list.add(new String[]{name, value});
        }
        return list;
    }

    /** 跳过某个元素的全部内容（用于 script/style 等） */
    private static int skipElement(String input, int from, String tag) {
        String close = "</" + tag;
        int depth = 1;
        int i = from;
        while (i < input.length() && depth > 0) {
            int nextOpen = indexOfIgnoreCase(input, "<" + tag, i);
            int nextClose = indexOfIgnoreCase(input, close, i);
            if (nextClose < 0) return input.length();
            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++;
                i = nextOpen + tag.length() + 1;
            } else {
                depth--;
                int end = input.indexOf('>', nextClose);
                i = end < 0 ? input.length() : end + 1;
            }
        }
        return i;
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        int max = s.length() - needle.length();
        for (int i = Math.max(from, 0); i <= max; i++) {
            if (s.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    private static void appendEscaped(StringBuilder out, String text, int maxLen) {
        if (text.isEmpty() || out.length() >= maxLen) return;
        out.append(escapeText(text));
    }

    /** 文本转义（保留已有实体，避免 &amp; 变成 &amp;amp;） */
    private static String escapeText(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '&') {
                Matcher m = ENTITY.matcher(text);
                if (m.find(i) && m.start() == i) {
                    sb.append(m.group());
                    i = m.end();
                    continue;
                }
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }

    private static String escapeAttr(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 反转义常见实体（toPlainText 用） */
    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ").replace("&amp;", "&");
    }
}
