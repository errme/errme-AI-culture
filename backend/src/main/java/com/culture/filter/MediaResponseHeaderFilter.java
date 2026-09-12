package com.culture.filter;

import com.culture.util.ImageUtil;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 媒体（图片/视频）响应头统一加固过滤器。
 *
 * <p><b>为什么需要一个新过滤器，而不是改 {@code MyPicConfig.addResourceHandlers}：</b></p>
 * <ul>
 *   <li>要加固的路径分成两类：<b>Spring 静态资源处理器托管</b>的（{@code /upload/media/**}、
 *       {@code /static/upload/**}、{@code /culture/upload/**}，见 {@code MyPicConfig}
 *       的 {@code addResourceHandlers}）和<b>控制器直接写流</b>的
 *       （{@code FileUpload#showFmImg} / {@code #showphoto}）。</li>
 *   <li>在 {@code addResourceHandlers} 里 {@code setCacheControl} 只能覆盖前者，
 *       而且对同一个 URL 模式再注册一次资源处理器会<b>覆盖</b> {@code MyPicConfig} 里已有的映射
 *       （{@code ResourceHandlerRegistry} 按模式去重，后注册的生效），既要重复目录配置又容易踩坑；
 *       同时 {@code config} 目录当前由另一个任务（Redis 缓存 / 慢 SQL）占用，本任务约定不改动它。</li>
 *   <li>过滤器是唯一能<b>一处覆盖全部媒体出口</b>的做法：静态资源与控制器写流都会经过它，
 *       只按 URI 前缀+扩展名判断，不依赖具体是哪个 Handler 在服务。</li>
 * </ul>
 *
 * <p><b>加了什么</b>：</p>
 * <ol>
 *   <li>{@code X-Content-Type-Options: nosniff} —— 对媒体路径的兜底声明
 *       （{@code SecurityHeadersFilter} 已经全局设置过，这里用 {@code containsHeader} 判重，
 *       将来那个过滤器顺序变化或被移除时媒体路径仍有这层保护）；</li>
 *   <li>{@code Content-Disposition: inline; filename="xxx.jpg"} —— <b>仅图片</b>，
 *       明确「内联展示」并给出清洗过的 ASCII 文件名（防 CR/LF 头注入）。视频不加，
 *       保持浏览器默认行为（点开播放/下载）。</li>
 *   <li>{@code Cache-Control: public, max-age=604800} —— 仅静态媒体目录下的图片/视频
 *       （{@code ImageUtil.isMediaFileName}）。上传文件名是 UUID（内容不可变），可以放心长缓存；
 *       控制器路径（{@code /showFmImg/**}、{@code /showimage/**}）自己 {@code setHeader} 了
 *       {@code no-store}，控制器在过滤器之后执行会覆盖本头，因此那两条路径的
 *       「不缓存」语义保持不变（用 {@code containsHeader} 判重，不主动与既有配置打架）。</li>
 * </ol>
 *
 * <p>不改变任何访问控制：本过滤器只写响应头，不做鉴权/放行判断，
 * 前台匿名访问图片、后台访问图片的行为都与改造前一致。</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class MediaResponseHeaderFilter extends OncePerRequestFilter {

    /** 需要加固的媒体路径前缀：两个静态资源映射 + 两个控制器直出图片的路径 */
    private static final String[] MEDIA_PREFIXES = {
            "/upload/media/",   // MyPicConfig：富文本正文图片/视频
            "/static/upload/",  // MyPicConfig：Windows 下的头像/封面上传目录
            "/culture/upload/", // MyPicConfig：Linux/mac 下的头像/封面上传目录
            "/showFmImg/",      // FileUpload#showFmImg（封面）
            "/showimage/"       // FileUpload#showphoto（头像）
    };

    /** 静态媒体的浏览器缓存时长：7 天（文件名含 UUID，内容不可变） */
    private static final long MEDIA_CACHE_SECONDS = 7L * 24 * 60 * 60;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri == null || !isMediaPath(uri)) {
            chain.doFilter(request, response);
            return;
        }

        // 1) 禁止 MIME 嗅探（兜底；SecurityHeadersFilter 已全局设置过同样的头）
        if (!response.containsHeader("X-Content-Type-Options")) {
            response.setHeader("X-Content-Type-Options", "nosniff");
        }

        String fileName = baseName(uri);
        boolean media = ImageUtil.isMediaFileName(fileName);
        if (media) {
            // 2) 静态媒体长缓存（文件名含 UUID，内容不可变）；
            //    控制器路径（/showFmImg/**、/showimage/**）自己 setHeader 了 no-store，
            //    控制器在过滤器之后执行会覆盖本头，因此那两条路径的「不缓存」语义不变。
            if (!response.containsHeader("Cache-Control")) {
                response.setHeader("Cache-Control", "public, max-age=" + MEDIA_CACHE_SECONDS);
            }
        }
        if (ImageUtil.isImageFileName(fileName)) {
            // 3) 仅图片：内联展示 + 安全文件名（只保留 ASCII 白名单字符，杜绝头注入）。
            //    视频不加 Content-Disposition，保持浏览器默认行为（点开播放/下载）。
            if (!response.containsHeader("Content-Disposition")) {
                response.setHeader("Content-Disposition", "inline; filename=\"" + safeHeaderFileName(fileName) + "\"");
            }
        }
        chain.doFilter(request, response);
    }

    /** URI 是否命中媒体路径前缀 */
    private static boolean isMediaPath(String uri) {
        for (String prefix : MEDIA_PREFIXES) {
            if (uri.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 取 URI 的最后一段作为文件名（用于按扩展名判断是否图片） */
    private static String baseName(String uri) {
        int slash = uri.lastIndexOf('/');
        return slash < 0 ? uri : uri.substring(slash + 1);
    }

    /** 响应头文件名清洗：只保留 [A-Za-z0-9._-]，其余一律替换为下划线 */
    private static String safeHeaderFileName(String name) {
        if (name == null || name.isEmpty()) return "image";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }
}
