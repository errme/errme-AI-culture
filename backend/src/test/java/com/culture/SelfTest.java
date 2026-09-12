package com.culture;

import com.culture.auth.service.JwtService;
import com.culture.util.CsvUtil;
import com.culture.util.HtmlSanitizer;
import com.culture.util.ImageUtil;
import com.culture.util.SearchUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 零依赖自测：本地 Maven 仓库里没有 JUnit（离线环境），因此用 main + 断言方式跑关键纯函数。
 *
 * 编译并运行（Windows，仓库根目录下执行）：
 *   cd backend
 *   javac -encoding UTF-8 -d target/test-classes -cp target/classes src/test/java/com/culture/SelfTest.java
 *   java -Dfile.encoding=UTF-8 -cp "target/classes;target/test-classes" com.culture.SelfTest
 * Linux/macOS 把 classpath 的 ';' 换成 ':'。
 *
 * 覆盖：XSS 白名单清洗、搜索关键词转义、CSV 转义与公式注入防护、缩略图文件名推导。
 * scripts/ci.sh 已把它接入流水线。
 */
public final class SelfTest {

    private static int passed = 0;
    private static final List<String> FAILED = new ArrayList<>();

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            FAILED.add(name + (detail == null || detail.isEmpty() ? "" : " -> " + detail));
            System.out.println("  ✗ " + name + (detail == null || detail.isEmpty() ? "" : " -> " + detail));
        }
    }

    public static void main(String[] args) {
        System.out.println("== HtmlSanitizer：XSS 白名单清洗 ==");
        xss();

        System.out.println("== SearchUtil：关键词与 LIKE 转义 ==");
        search();

        System.out.println("== CsvUtil：CSV 转义与公式注入防护 ==");
        csv();

        System.out.println("== ImageUtil：缩略图文件名推导 ==");
        thumb();

        System.out.println("== JwtService：令牌作用域隔离与滑动续期 TTL ==");
        jwt();

        System.out.println();
        if (FAILED.isEmpty()) {
            System.out.println("全部通过：" + passed + " 项");
            System.exit(0);
        }
        System.out.println("失败 " + FAILED.size() + " 项（通过 " + passed + " 项）：");
        for (String f : FAILED) System.out.println("  - " + f);
        System.exit(1);
    }

    private static void xss() {
        String[] payloads = {
            "<script>alert(1)</script>你好",
            "<img src=x onerror=alert(1)>你好",
            "<a href=\"javascript:alert(1)\">点我</a>",
            "<iframe src=\"http://evil.com\"></iframe>正文",
            "<div onmouseover=\"alert(1)\">悬停</div>",
            "<style>body{display:none}</style>样式",
            "<svg onload=alert(1)></svg>图形",
            "<a href=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\">数据链接</a>",
            "<p onclick=alert(1)>段落</p>",
            "<object data=\"evil.swf\"></object>对象"
        };
        String[] forbidden = {"<script", "<iframe", "<style", "<svg", "<object", "onerror", "onload", "onclick", "onmouseover", "javascript:", "data:text/html"};
        StringBuilder leaks = new StringBuilder();
        for (String raw : payloads) {
            String out = HtmlSanitizer.sanitize(raw);
            if (out == null) { leaks.append("null;"); continue; }
            String lower = out.toLowerCase();
            for (String f : forbidden) {
                if (lower.contains(f)) leaks.append(f).append(" 残留于[").append(raw).append("]; ");
            }
        }
        check("危险标签/属性/协议全部被剔除（10 组载荷）", leaks.length() == 0, leaks.toString());

        String kept = HtmlSanitizer.sanitize("<p>正文<strong>加粗</strong></p><script>x</script>");
        check("正常文本与基础标签保留", kept != null && kept.contains("正文") && kept.contains("加粗"),
            "结果=" + kept);

        String plain = HtmlSanitizer.toPlainText("<p>旗袍<br/>传统服饰</p>");
        check("toPlainText 去掉标签保留文字",
            plain != null && plain.contains("旗袍") && plain.contains("传统服饰") && !plain.contains("<"),
            "结果=" + plain);
    }

    private static void search() {
        check("normalizeKeyword 去首尾空白", "旗袍".equals(SearchUtil.normalizeKeyword("  旗袍  ")),
            "结果=" + SearchUtil.normalizeKeyword("  旗袍  "));
        String longKw = new String(new char[80]).replace('\0', 'a');
        String norm = SearchUtil.normalizeKeyword(longKw);
        check("normalizeKeyword 截断到上限 " + SearchUtil.MAX_KEYWORD_LENGTH,
            norm != null && norm.length() == SearchUtil.MAX_KEYWORD_LENGTH, "长度=" + (norm == null ? -1 : norm.length()));
        String nullKw = SearchUtil.normalizeKeyword(null);
        check("normalizeKeyword 处理 null/空", nullKw == null || nullKw.isEmpty(), "结果=" + nullKw);

        String pattern = SearchUtil.toLikePattern("100%_test!");
        check("toLikePattern 首尾加 %", pattern != null && pattern.startsWith("%") && pattern.endsWith("%"), "结果=" + pattern);
        check("toLikePattern 转义 % 与 _",
            pattern != null && pattern.contains(SearchUtil.LIKE_ESCAPE_CHAR + "%") && pattern.contains(SearchUtil.LIKE_ESCAPE_CHAR + "_"),
            "结果=" + pattern);
        check("toLikePattern 对空关键词返回安全值",
            SearchUtil.toLikePattern(null) == null || SearchUtil.toLikePattern(null).indexOf('%') == -1,
            "结果=" + SearchUtil.toLikePattern(null));
    }

    private static void csv() {
        check("普通值原样输出", "abc".equals(CsvUtil.cell("abc")), CsvUtil.cell("abc"));
        check("含逗号加引号", "\"a,b\"".equals(CsvUtil.cell("a,b")), CsvUtil.cell("a,b"));
        check("含双引号双写并加引号", "\"a\"\"b\"".equals(CsvUtil.cell("a\"b")), CsvUtil.cell("a\"b"));
        check("含换行加引号", "\"a\nb\"".equals(CsvUtil.cell("a\nb")), CsvUtil.cell("a\nb"));
        check("null 输出空串", "".equals(CsvUtil.cell(null)), CsvUtil.cell(null));
        check("公式注入 = 前置单引号", "'=1+1".equals(CsvUtil.cell("=1+1")), CsvUtil.cell("=1+1"));
        check("公式注入 + 前置单引号", "'+1".equals(CsvUtil.cell("+1")), CsvUtil.cell("+1"));
        check("公式注入 - 前置单引号", "'-1".equals(CsvUtil.cell("-1")), CsvUtil.cell("-1"));
        check("公式注入 @ 前置单引号", "'@SUM(A1)".equals(CsvUtil.cell("@SUM(A1)")), CsvUtil.cell("@SUM(A1)"));
        String row = CsvUtil.row("1", "标题", "作者");
        check("row 用逗号连接且以 CRLF 结尾", row != null && row.startsWith("1,标题,作者") && row.endsWith("\r\n"), "行=" + row);
        check("BOM 为 UTF-8 BOM 三字节",
            CsvUtil.BOM.length == 3 && (CsvUtil.BOM[0] & 0xFF) == 0xEF && (CsvUtil.BOM[1] & 0xFF) == 0xBB && (CsvUtil.BOM[2] & 0xFF) == 0xBF,
            "长度=" + CsvUtil.BOM.length);
    }

    private static void jwt() {
        String frontSecret = "culture-front-secret-key-please-change-0123456789abcdefghijklmnopqrstuvwxyz";
        String adminSecret = "culture-admin-secret-key-please-change-0123456789abcdefghijklmnopqrstuvwxyz";
        JwtService svc = new JwtService(frontSecret, adminSecret, 24, 8);

        long frontTtl = svc.ttlMillis(JwtService.Scope.FRONT);
        long adminTtl = svc.ttlMillis(JwtService.Scope.ADMIN);
        check("前台令牌 TTL = 24h", frontTtl == 24L * 3600 * 1000, "ttl=" + frontTtl);
        check("后台令牌 TTL = 8h（滑动续期按此判断剩余一半）", adminTtl == 8L * 3600 * 1000, "ttl=" + adminTtl);

        String adminToken = svc.generateToken(7L, "admin@qq.com", JwtService.Scope.ADMIN);
        check("后台令牌可解析出用户ID", Long.valueOf(7L).equals(svc.getUserId(adminToken, JwtService.Scope.ADMIN)),
            "userId=" + svc.getUserId(adminToken, JwtService.Scope.ADMIN));
        check("作用域隔离：前台密钥解不了后台令牌", !canParse(svc, adminToken, JwtService.Scope.FRONT), "前台作用域解析应失败");
        String frontToken = svc.generateToken(9L, "u@qq.com", JwtService.Scope.FRONT);
        check("前台令牌不能被后台作用域解析", !canParse(svc, frontToken, JwtService.Scope.ADMIN), "后台作用域解析应失败");

        long remaining = svc.parse(adminToken, JwtService.Scope.ADMIN).getExpiration().getTime() - System.currentTimeMillis();
        check("新签发令牌剩余有效期大于一半（不会立刻触发续期）", remaining > adminTtl / 2, "remaining=" + remaining);
    }

    private static boolean canParse(JwtService svc, String token, JwtService.Scope scope) {
        try {
            svc.getUserId(token, scope);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void thumb() {
        check("png 推导为 _thumb.jpg", "a_thumb.jpg".equals(ImageUtil.thumbFileName("a.png")), ImageUtil.thumbFileName("a.png"));
        check("jpeg 推导为 _thumb.jpg", "b_thumb.jpg".equals(ImageUtil.thumbFileName("b.jpeg")), ImageUtil.thumbFileName("b.jpeg"));
        check("GIF 不生成缩略图", ImageUtil.thumbFileName("c.gif") == null, String.valueOf(ImageUtil.thumbFileName("c.gif")));
        check("已是缩略图不再推导", ImageUtil.thumbFileName("d_thumb.jpg") == null, String.valueOf(ImageUtil.thumbFileName("d_thumb.jpg")));
        // thumbFileName 只做「文件名 -> 文件名」的纯推导（会把 URL 当路径取末段）；
        // 外链判定在 thumbUrl 里，调用方应统一用 thumbUrl 处理 URL。
        check("外链不推导（thumbUrl 返回 null）", ImageUtil.thumbUrl("http://x/a.png") == null,
            String.valueOf(ImageUtil.thumbUrl("http://x/a.png")));
        check("thumbFileName 对带目录的路径只取文件名", "x_thumb.jpg".equals(ImageUtil.thumbFileName("a/b/x.png")),
            String.valueOf(ImageUtil.thumbFileName("a/b/x.png")));
        check("无扩展名不推导", ImageUtil.thumbFileName("noext") == null, String.valueOf(ImageUtil.thumbFileName("noext")));
        check("thumbUrl 保留目录前缀", "/showFmImg/x_thumb.jpg".equals(ImageUtil.thumbUrl("/showFmImg/x.jpg")), String.valueOf(ImageUtil.thumbUrl("/showFmImg/x.jpg")));
        check("isThumb 识别缩略图", ImageUtil.isThumb("x_thumb.jpg") && !ImageUtil.isThumb("x.jpg"), "x_thumb.jpg=" + ImageUtil.isThumb("x_thumb.jpg"));
    }
}
