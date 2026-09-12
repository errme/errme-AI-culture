package com.culture.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * HTML 白名单清洗器（零依赖实现）。
 *
 * <p>用途：<b>用户提交的内容</b>（评论等 UGC）入库前必须清洗，否则任何访问者都能提交
 * {@code <script>} / {@code onerror=} 之类的载荷，形成存储型 XSS。后台管理员录入的正文
 * 属于可信内容，不走这里（富文本编辑器保留其排版能力）。</p>
 *
 * <p>策略：</p>
 * <ul>
 *   <li>标签白名单：只保留基础排版标签；其它标签一律丢弃（内容保留，标签本身去掉）。</li>
 *   <li>{@code script/style/iframe/svg/...} 等危险标签连同内部文本一起丢弃。</li>
 *   <li>属性白名单：仅 {@code a[href|title]}，其它属性（含所有 {@code on*} 事件属性、{@code style}）全部丢弃。</li>
 *   <li>URL 协议白名单：{@code http(s)://}、站内 {@code /}、锚点 {@code #}、{@code mailto:}；
 *       {@code javascript:} / {@code data:} / {@code vbscript:} 等一律拒绝。</li>
 *   <li>文本与属性值统一实体转义，避免拼接出新的标签。</li>
 * </ul>
 */
public final class HtmlSanitizer {

    private HtmlSanitizer() { }

    /** 允许保留的标签 */
    private static final Set<String> ALLOWED_TAGS = new HashSet<>(Arrays.asList(
            "p", "br", "strong", "b", "em", "i", "u", "s", "del", "ins",
            "blockquote", "code", "pre", "ul", "ol", "li", "a",
            "h1", "h2", "h3", "h4", "h5", "h6"));

    /** 自闭合标签 */
    private static final Set<String> VOID_TAGS = new HashSet<>(Collections.singletonList("br"));

    /** 连同内部文本一起丢弃的标签 */
    private static final Set<String> DROP_WITH_CONTENT = new HashSet<>(Arrays.asList(
            "script", "style", "iframe", "object", "embed", "template", "noscript",
            "svg", "math", "link", "meta", "base", "form", "input", "button",
            "textarea", "select", "option", "applet", "frame", "frameset", "xml"));

    /** 各标签允许保留的属性 */
    private static final Map<String, Set<String>> ALLOWED_ATTRS = new HashMap<>();

    static {
        ALLOWED_ATTRS.put("a", new HashSet<>(Arrays.asList("href", "title")));
    }

    private static final String[] URL_PREFIXES = {"http://", "https://", "/", "#", "mailto:"};

    private static final Pattern ENTITY = Pattern.compile("&[a-zA-Z][a-zA-Z0-9]{1,10};|&#\\d{1,7};|&#[xX][0-9a-fA-F]{1,6};");

    /** 清洗（默认长度上限 2000 字符） */
    public static String sanitize(String html) {
        return sanitize(html, 2000);
    }

    /**
     * 白名单清洗。
     *
     * @param html   原始 HTML（可为 null）
     * @param maxLen 清洗后最大长度（按字符截断，避免超长内容撑爆库）
     * @return 可安全输出的 HTML 片段
     */
    public static String sanitize(String html, int maxLen) {
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

            if (DROP_WITH_CONTENT.contains(tag)) {
                // 连同内部文本一起丢弃（找配对的结束标签）
                i = skipElement(input, i, tag);
                continue;
            }
            if (!ALLOWED_TAGS.contains(tag)) {
                continue;                  // 非白名单标签：只丢标签，保留内部文本
            }
            if (VOID_TAGS.contains(tag)) {
                out.append('<').append(tag).append('>');
                continue;
            }
            if (closing) {
                out.append("</").append(tag).append('>');
                continue;
            }

            // 开标签：仅保留白名单属性
            StringBuilder tagOut = new StringBuilder("<").append(tag);
            Set<String> allowed = ALLOWED_ATTRS.get(tag);
            if (allowed != null && !allowed.isEmpty()) {
                for (String[] attr : parseAttributes(body.substring(tag.length()))) {
                    String name = attr[0].toLowerCase(Locale.ROOT);
                    String value = attr[1];
                    if (!allowed.contains(name)) continue;
                    if ("href".equals(name) && !isSafeUrl(value)) continue;
                    if ("href".equals(name) && value.startsWith("http")) {
                        tagOut.append(" rel=\"nofollow noopener\" target=\"_blank\"");
                    }
                    tagOut.append(' ').append(name).append("=\"").append(escapeAttr(value)).append('"');
                }
            }
            tagOut.append('>');
            if (out.length() < maxLen) out.append(tagOut);
            if (out.length() >= maxLen) break;
        }

        String result = out.toString();
        return result.length() > maxLen ? result.substring(0, maxLen) : result;
    }

    /** 去掉全部标签，仅保留纯文本（用于昵称、摘要、搜索关键词等场景） */
    public static String toPlainText(String html) {
        if (html == null) return "";
        String noTag = html.replaceAll("(?s)<[^>]*>", "");
        return unescape(noTag).trim();
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
                java.util.regex.Matcher m = ENTITY.matcher(text);
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
