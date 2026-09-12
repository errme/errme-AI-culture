package com.culture.util;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * CSV 导出工具（后台批量导出用）。
 *
 * <p>约定与安全要点：</p>
 * <ol>
 *   <li><b>UTF-8 BOM</b>：导出前先写出 {@link #BOM}，Excel/WPS 双击打开才不会把中文按 ANSI 解码（乱码根因）；</li>
 *   <li><b>RFC4180 转义</b>：单元格含 逗号 / 双引号 / 换行(CR、LF) 时用双引号整体包裹，
 *       内部的双引号翻倍（" → ""），否则一行会被拆成多列、多行；</li>
 *   <li><b>公式注入防护</b>：单元格以 = + - @ 开头时前面补一个单引号，
 *       防止 Excel/WPS 把用户可编辑的内容当公式执行（CSV Injection）；</li>
 *   <li>纯静态工具类，无状态；{@link SimpleDateFormat} 非线程安全，故每次调用新建实例。</li>
 * </ol>
 */
public final class CsvUtil {

    /** UTF-8 BOM 字节（EF BB BF）：写文件最前面，Excel 靠它识别 UTF-8 */
    public static final byte[] BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** 行分隔符：RFC4180 规定 CRLF，Excel 兼容性最好 */
    private static final String CRLF = "\r\n";

    /** 日期格式（导出列统一格式，避免各 JDBC 驱动 toString 不一致） */
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private CsvUtil() {
    }

    /**
     * 拼一行 CSV 文本（含结尾 CRLF），入参为各单元格的原始值（未转义）。
     */
    public static String row(String... cells) {
        StringBuilder sb = new StringBuilder();
        if (cells != null) {
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(cell(cells[i]));
            }
        }
        return sb.append(CRLF).toString();
    }

    /**
     * 转义单个单元格：先做公式注入防护，再按 RFC4180 决定是否加双引号包裹。
     */
    public static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String v = value;
        // 公式注入：=SUM(...) / +1 / -1 / @cmd 这类内容会被 Excel 当公式执行，补单引号使其成为纯文本
        char first = v.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            v = "'" + v;
        }

        boolean needQuote = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                needQuote = true;
                break;
            }
        }
        if (!needQuote) {
            return v;
        }

        StringBuilder sb = new StringBuilder(v.length() + 8);
        sb.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"') {
                sb.append('"');     // 内部双引号翻倍
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }

    /**
     * 日期格式化（yyyy-MM-dd HH:mm:ss）。
     * 兼容 {@link java.sql.Timestamp}（Map 方式查询时 JDBC 返回的实际类型）；null 返回空串，
     * 其它类型退化为 String.valueOf。
     */
    public static String formatDateTime(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Date) {
            return new SimpleDateFormat(DATE_PATTERN).format((Date) value);
        }
        return String.valueOf(value);
    }

    /** 宽松转 Long（数字 / 字符串都可以），失败返回 null，供导出时读取 Map 结果使用 */
    public static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
