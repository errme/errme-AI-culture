package com.culture.controller.admin;


import com.culture.entity.Culture;
import com.culture.entity.User;
import com.culture.service.CultureService;
import com.culture.service.UserService;
import com.culture.util.AjaxResult;
import org.apache.poi.util.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.UUID;

@Controller
public class FileUpload {

    @Autowired
    private UserService userService;

    @Autowired
    private CultureService cultureService;

    @Value("${avatar.upload.path}")
    private String uploadPath;

    @Value("${culture.upload.path}")
    private String cultureFmPath;

    /** 富文本正文媒体根目录（图片/视频，URL 前缀 /upload/media/**） */
    @Value("${editor.upload.path}")
    private String editorPath;

    /**
     * 上传大小上限的兜底值（来自 application.yml）。
     * 实际取值优先读数据库（后台「系统设置 → 上传限制」），改完即时生效。
     */
    @Value("${editor.upload.max-image-mb:10}")
    private int editorMaxImageMbFallback;

    @Value("${editor.upload.max-video-mb:200}")
    private int editorMaxVideoMbFallback;

    /** 系统配置来源（上传上限等运行期可变项） */
    @Autowired
    private com.culture.service.ConfigService configService;

    /** 单张图片上限（MB）：后台可改，未配置时回退 application.yml */
    private int editorMaxImageMb() {
        return configService.getInt("upload.max-image-mb", editorMaxImageMbFallback);
    }

    /** 单个视频上限（MB）：后台可改，未配置时回退 application.yml */
    private int editorMaxVideoMb() {
        return configService.getInt("upload.max-video-mb", editorMaxVideoMbFallback);
    }

    /** 用于「本人或管理员」鉴权判断（头像上传） */
    @org.springframework.beans.factory.annotation.Autowired
    private com.culture.auth.service.AuthService authService;

    /**
     * 允许上传的图片扩展名（头像/封面/正文图片统一白名单）。
     *
     * <p><b>为什么刻意不含 svg / html / js 等类型</b>（本次加固核对并写进注释）：</p>
     * <ul>
     *   <li><b>svg</b>：SVG 本质是 XML 文档，可以内嵌 {@code <script>}、{@code onload} 事件与外链，
     *       浏览器把它当图片打开时是在<b>本站同源</b>下解析执行的 —— 等于给攻击者一个存储型 XSS 入口
     *       （可以偷 JWT/操作后台接口）。它无法靠「文件头嗅探」拦住：SVG 就是文本，改扩展名成本为零。</li>
     *   <li><b>html/htm/xhtml/xml</b>：同样是可被浏览器解析执行的文档类型，同源打开即可执行脚本。</li>
     *   <li><b>js/mjs/css</b>：静态资源目录下的脚本会被同源加载，等于可控的脚本托管点。</li>
     * </ul>
     * <p>白名单只留真正的位图格式；配合 {@link #looksLikeImage} 的文件头嗅探与
     * {@code ImageUtil#reencode} 二次编码，改扩展名/夹带尾巴都进不来。</p>
     */
    private static final java.util.Set<String> IMAGE_EXT = new java.util.HashSet<>(java.util.Arrays.asList(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"));

    /**
     * 明确拒绝的可执行/可解析类型：即使它们本来就不在白名单里，也单独判一次并给出明确报错。
     * 目的是「双保险 + 可读的错误信息」：将来若有人为了业务放开白名单，这里仍然会拦住这些类型。
     */
    private static final java.util.Set<String> DENY_EXT = new java.util.HashSet<>(java.util.Arrays.asList(
            ".svg", ".svgz", ".html", ".htm", ".xhtml", ".xml", ".js", ".mjs", ".css", ".swf"));

    /** 扩展名是否属于「可执行/可解析」类型（svg/html/js 等，见 {@link #DENY_EXT}） */
    private static boolean isDeniedExt(String ext) {
        return ext != null && DENY_EXT.contains(ext.toLowerCase());
    }

    /** 校验图片并返回规范化的扩展名；不合法时抛出 IllegalArgumentException */
    private String requireImage(MultipartFile file, long maxBytes) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("文件为空");
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf(".")).toLowerCase()
                : "";
        // 先给出「明确拒绝」的提示（svg/html/js 等），再走白名单兜底：
        // 这两者都拒绝，但提示信息的可读性不同，便于前端/运营定位问题
        if (isDeniedExt(ext)) {
            throw new IllegalArgumentException("不允许上传 " + ext + " 类型：svg/html/js 等可执行文件会在浏览器同源执行，存在 XSS 风险");
        }
        if (!IMAGE_EXT.contains(ext)) {
            throw new IllegalArgumentException("仅支持图片格式：jpg/jpeg/png/gif/webp/bmp");
        }
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("图片过大，请不超过 " + (maxBytes / 1024 / 1024) + "MB");
        }
        // 简单内容嗅探：图片文件头（防止改扩展名上传其它内容）
        try (java.io.InputStream in = file.getInputStream()) {
            byte[] head = new byte[12];
            int n = in.read(head);
            if (n < 4 || !looksLikeImage(head, n)) {
                throw new IllegalArgumentException("文件内容不是有效图片");
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("读取文件失败");
        }
        return ext;
    }

    private boolean looksLikeImage(byte[] h, int n) {
        // JPEG / PNG / GIF / BMP / WEBP(RIFF....WEBP)
        if (h[0] == (byte) 0xFF && h[1] == (byte) 0xD8) return true;
        if (n >= 8 && h[0] == (byte) 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return true;
        if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F') return true;
        if (h[0] == 'B' && h[1] == 'M') return true;
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return true;
        return false;
    }

    //上传头像
    @RequestMapping(value = "/file/uploadFile", method = RequestMethod.POST)
    @ResponseBody
    public AjaxResult upload(HttpServletRequest req, Integer id, @RequestParam("file") MultipartFile file) {
        try {
            // ===== 鉴权：普通用户只能改自己的头像；管理员可指定用户（后台用户管理需要）=====
            Long callerId = (Long) req.getAttribute(com.culture.api.JwtAuthFilter.ATTR_LOGIN_USER_ID);
            if (callerId == null) {
                return new AjaxResult("未登录或凭证无效");
            }
            boolean isAdmin = authService.isAdmin(callerId);
            if (!isAdmin && id != null && !callerId.equals(Long.valueOf(id))) {
                return new AjaxResult("只能修改本人的头像");
            }
            long targetUserId = (isAdmin && id != null) ? id.longValue() : callerId;

            // ===== 类型与内容校验（原实现不做任何校验，可上传 html/脚本造成 XSS）=====
            String suffixName = requireImage(file, editorMaxImageMb() * 1024L * 1024L);

            String uuidString = UUID.randomUUID().toString();
            //新文件名
            String newFileName = uuidString + suffixName;


            File path = new File(uploadPath);
            //检测是否存在目录
            if (!path.exists()) path.mkdirs();

            File savefile = new File(path, newFileName);
            if (!savefile.getParentFile().exists()) savefile.getParentFile().mkdirs();
            //保存图片
            file.transferTo(savefile);
            // 上传加固：先原地二次编码（jpg/jpeg/png 去掉 EXIF 与尾部附加数据；
            // GIF/无法解码的保持原文件；失败也保持原文件，不影响上传）
            boolean reencoded = com.culture.util.ImageUtil.reencode(savefile);
            if (!reencoded) {
                System.out.println("[上传] 头像未二次编码（GIF/非 jpg-png/解码失败），保留原文件：" + newFileName);
            }
            // 过大时压缩（失败不影响上传；reencode 已按同一阈值缩过，这里通常是无操作）
            com.culture.util.ImageUtil.compress(savefile, com.culture.util.ImageUtil.COMPRESS_MAX_WIDTH);
            // 多档缩略图：xxx_thumb.jpg(480，历史档) + xxx_thumb_320/640/1200.jpg，一次解码全部生成；
            // 失败不影响上传成功
            writeThumbQuietly(savefile);

            //更新用户表的头像（目标用户由上面的鉴权逻辑决定，不再直接信任前端传入的 id）
            User user = new User();
            user.setId(targetUserId);
            user.setHeadImg(newFileName);
            userService.updateUserHeadImg(user);

            return new AjaxResult();
        } catch (IllegalArgumentException e) {
            return new AjaxResult(e.getMessage());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    //显示头像
    @RequestMapping(value = "/showimage/{image_name}")
    public String showphoto(@PathVariable("image_name") String image_name, HttpServletRequest request, HttpServletResponse response)
            throws Exception {

        response.setDateHeader("Expires", 0);
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.addHeader("Cache-Control", "post-check=0, pre-check=0");
        response.setHeader("Pragma", "no-cache");

        // 上传加固（本次增量）：
        //  1) 路径参数只允许纯文件名并做 canonical 目录校验，堵住 ../ 越权读取；
        //  2) Content-Type 按真实扩展名给（不再一律 image/jpeg，png/gif/webp 也能正确渲染）；
        //  3) X-Content-Type-Options: nosniff + Content-Disposition: inline（图片）——
        //     与 MediaResponseHeaderFilter 的全局加固一致，这里对「直接写流」的分支再显式设置一次；
        //  4) 文件不存在返回 404，不再抛 FileNotFoundException（原来会变成 500）。
        File file = resolveUploadedFile(uploadPath, image_name);
        System.out.println("读取头像:" + image_name);
        if (file == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        writeImageToResponse(response, file);
        return null;
    }


    //上传封面
    @RequestMapping(value = "/file/uploadCultureFmFile", method = RequestMethod.POST)
    @ResponseBody
    public AjaxResult uploadCultureFmFile(HttpServletRequest req, Integer id, @RequestParam("file") MultipartFile file) {
        try {
            // ===== 鉴权：封面属于内容，只有管理员可改（原先任何登录用户都能改任意文化的封面）=====
            Long callerId = (Long) req.getAttribute(com.culture.api.JwtAuthFilter.ATTR_LOGIN_USER_ID);
            if (callerId == null || !authService.isAdmin(callerId)) {
                return new AjaxResult("无权限：仅管理员可修改文化封面");
            }
            if (id == null || id <= 0) {
                return new AjaxResult("缺少文化 id");
            }
            if (cultureService.findDetailById(id.longValue()) == null) {
                return new AjaxResult("文化不存在：" + id);
            }
            String suffixName = requireImage(file, editorMaxImageMb() * 1024L * 1024L);
            String uuidString = UUID.randomUUID().toString();
            String newFileName = uuidString + suffixName;

            File path = new File(cultureFmPath);
            if (!path.exists()) path.mkdirs();

            File savefile = new File(path, newFileName);
            if (!savefile.getParentFile().exists()) savefile.getParentFile().mkdirs();
            file.transferTo(savefile);
            // 上传加固：先原地二次编码（jpg/jpeg/png 去 EXIF/尾部数据；GIF/解码失败保持原文件），
            // 再压缩（reencode 已限宽，通常无效），最后一次性生成多档缩略图；都不影响上传成功
            if (!com.culture.util.ImageUtil.reencode(savefile)) {
                System.out.println("[上传] 封面未二次编码（GIF/非 jpg-png/解码失败），保留原文件：" + newFileName);
            }
            com.culture.util.ImageUtil.compress(savefile, com.culture.util.ImageUtil.COMPRESS_MAX_WIDTH);
            writeThumbQuietly(savefile);

            //更新封面图片
            Culture culture = new Culture();
            culture.setId(Long.parseLong(id + ""));
            culture.setFmUrl(newFileName);
            cultureService.updateCultureFmUrl(culture);

            return new AjaxResult();
        } catch (IllegalArgumentException e) {
            return new AjaxResult(e.getMessage());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    //显示封面
    @RequestMapping(value = "/showFmImg/{image_name}")
    public String showFmImg(@PathVariable("image_name") String image_name, HttpServletRequest request, HttpServletResponse response)
            throws Exception {

        response.setDateHeader("Expires", 0);
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.addHeader("Cache-Control", "post-check=0, pre-check=0");
        response.setHeader("Pragma", "no-cache");

        // 与 showphoto 同一套加固：文件名白名单 + canonical 校验（防 ../）、按扩展名给 Content-Type、
        // nosniff + Content-Disposition: inline、文件不存在给 404。
        File file = resolveUploadedFile(cultureFmPath, image_name);
        System.out.println("读取封面:" + image_name);
        if (file == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        writeImageToResponse(response, file);
        return null;
    }

    // ==================== 图片读取/响应加固（本次增量新增的内部工具） ====================

    /**
     * 把 {@code /showFmImg/{name}}、{@code /showimage/{name}} 的路径参数解析成上传目录内的真实文件。
     *
     * <p>只接受<b>纯文件名</b>（不含 / 与 \），并用 canonical path 二次确认落在 rootPath 目录内，
     * 因此 {@code ..}、{@code ..%5C}、绝对路径之类的越权读取全部返回 null → 404。</p>
     *
     * @return 存在且合法的文件；否则 null
     */
    private static File resolveUploadedFile(String rootPath, String name) {
        if (rootPath == null || name == null || name.trim().isEmpty()) return null;
        String fileName = name.trim();
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) return null;
        File root = new File(rootPath);
        File file = new File(root, fileName);
        try {
            String rootCanon = root.getCanonicalPath();
            String fileCanon = file.getCanonicalPath();
            if (!fileCanon.startsWith(rootCanon + File.separator)) return null;
        } catch (IOException e) {
            return null;
        }
        return file.isFile() ? file : null;
    }

    /**
     * 图片响应统一写流（替代原来两处「硬编码 image/jpeg + 直接 copy」的代码）。
     *
     * <ul>
     *   <li>{@code Content-Type} 按扩展名给（{@code ImageUtil.contentTypeOf}）：png/gif/webp/bmp
     *       不再被错标成 jpeg；不认识的扩展名给 {@code application/octet-stream}；</li>
     *   <li>{@code X-Content-Type-Options: nosniff}：禁止浏览器自行嗅探类型
     *       （历史遗留的 svg/html 之类即使躺在目录里也不会被当作可执行内容渲染）；</li>
     *   <li>{@code Content-Disposition: inline; filename="..."}：图片内联展示（文件名已做 ASCII 白名单清洗，
     *       不会出现头注入）；</li>
     *   <li>输入流用 try-with-resources 关闭（原实现只关了输出流，文件句柄会泄漏到 GC）。</li>
     * </ul>
     */
    private static void writeImageToResponse(HttpServletResponse response, File file) throws IOException {
        String name = file.getName();
        response.setContentType(com.culture.util.ImageUtil.contentTypeOf(name));
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Disposition", "inline; filename=\"" + safeHeaderFileName(name) + "\"");
        ServletOutputStream out = response.getOutputStream();
        try (java.io.InputStream in = new FileInputStream(file)) {
            IOUtils.copy(in, out);
            out.flush();
        } finally {
            out.close();
        }
    }

    /** 响应头里的文件名只保留 ASCII 安全字符，防止 CR/LF 头注入与非法字符 */
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
    // ==================== 富文本正文媒体上传（新目录 upload/media，图片与视频分离） ====================

    /**
     * 富文本正文图片上传。
     * 落盘：{editor.upload.path}/image/yyyyMM/uuid.ext，访问：/upload/media/image/yyyyMM/uuid.ext
     */
    @RequestMapping(value = "/file/uploadEditorImage", method = RequestMethod.POST)
    @ResponseBody
    public java.util.Map<String, Object> uploadEditorImage(@RequestParam("file") MultipartFile file) {
        return saveEditorMedia(file, "image");
    }

    /**
     * 富文本正文视频上传（mp4/webm/ogg/mov/m4v），上限 editor.upload.max-video-mb。
     * 落盘：{editor.upload.path}/video/yyyyMM/uuid.ext，访问：/upload/media/video/yyyyMM/uuid.ext
     */
    @RequestMapping(value = "/file/uploadEditorVideo", method = RequestMethod.POST)
    @ResponseBody
    public java.util.Map<String, Object> uploadEditorVideo(@RequestParam("file") MultipartFile file) {
        return saveEditorMedia(file, "video");
    }

    /**
     * 正文媒体统一保存逻辑（图片/视频分开目录，按月分子目录，避免单目录文件过多）。
     *
     * @param kind image | video
     */
    private java.util.Map<String, Object> saveEditorMedia(MultipartFile file, String kind) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        boolean video = "video".equals(kind);
        try {
            if (file == null || file.isEmpty()) {
                result.put("errno", 1);
                result.put("message", "文件为空");
                return result;
            }
            String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
            String suffix = originalName.contains(".")
                    ? originalName.substring(originalName.lastIndexOf(".")).toLowerCase()
                    : "";
            // 明确拒绝可执行/可解析类型（svg/html/js 等）：与 requireImage 同一套双保险
            if (isDeniedExt(suffix)) {
                result.put("errno", 1);
                result.put("message", "不允许上传 " + suffix + " 类型：svg/html/js 等可执行文件会在浏览器同源执行，存在 XSS 风险");
                return result;
            }
            java.util.Set<String> allow = video
                    ? new java.util.HashSet<>(java.util.Arrays.asList(".mp4", ".webm", ".ogg", ".ogv", ".mov", ".m4v"))
                    : new java.util.HashSet<>(java.util.Arrays.asList(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"));
            if (!allow.contains(suffix)) {
                result.put("errno", 1);
                result.put("message", video
                        ? "视频仅支持 mp4/webm/ogg/mov/m4v 格式"
                        : "图片仅支持 jpg/jpeg/png/gif/webp/bmp 格式");
                return result;
            }
            long maxBytes = (long) (video ? editorMaxVideoMb() : editorMaxImageMb()) * 1024 * 1024;
            if (file.getSize() > maxBytes) {
                result.put("errno", 1);
                result.put("message", "文件超过上限 " + (video ? editorMaxVideoMb() : editorMaxImageMb()) + "MB");
                return result;
            }
            if (!editorPath.endsWith("/") && !editorPath.endsWith("\\")) {
                editorPath = editorPath + File.separator;
            }
            String month = new java.text.SimpleDateFormat("yyyyMM").format(new java.util.Date());
            String relativeDir = kind + File.separator + month;
            File dir = new File(editorPath + relativeDir);
            if (!dir.exists()) dir.mkdirs();

            String newName = java.util.UUID.randomUUID().toString().replace("-", "") + suffix;
            File savefile = new File(dir, newName);
            file.transferTo(savefile);

            String url = "/upload/media/" + kind + "/" + month + "/" + newName;
            // 图片：二次编码（去 EXIF/尾部数据）+ 压缩原图 + 一次解码生成多档缩略图
            if (!video) {
                try {
                    long before = savefile.length();
                    // 上传加固：原地二次编码；GIF / webp / 解码失败返回 false，保持原文件
                    if (!com.culture.util.ImageUtil.reencode(savefile)) {
                        System.out.println("[上传] 正文图片未二次编码（GIF/非 jpg-png/解码失败），保留原文件：" + newName);
                    }
                    com.culture.util.ImageUtil.compress(savefile, com.culture.util.ImageUtil.COMPRESS_MAX_WIDTH);
                    java.io.File thumb = writeThumbQuietly(savefile);
                    if (thumb != null) {
                        result.put("thumbUrl", "/upload/media/" + kind + "/" + month + "/" + thumb.getName());
                    }
                    System.out.println("[上传] 图片压缩 " + before + " -> " + savefile.length() + " bytes");
                } catch (Exception ignore) {
                    // 图片处理失败不影响上传本身
                }
            }
            result.put("errno", 0);
            result.put("url", url);
            result.put("name", originalName);
            result.put("size", savefile.length());
        } catch (Exception e) {
            result.put("errno", 1);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 生成原图对应的<b>多档缩略图</b>（统一入口）：
     * {@code xxx.png} -&gt; 同目录 {@code xxx_thumb.jpg}（480，历史档，返回它）
     * + {@code xxx_thumb_320.jpg} / {@code xxx_thumb_640.jpg} / {@code xxx_thumb_1200.jpg}。
     *
     * <p>多档一次解码产出（{@link com.culture.util.ImageUtil#writeThumbnails}），
     * 避免同一张大图被解码 4 次；任何失败都不抛出、不影响上传主流程；GIF 不生成。
     * 返回值保持与改造前一致：历史 480 档文件，失败/不适用返回 null
     * （调用方用它拼 {@code thumbUrl}，前端老引用因此不受影响）。</p>
     */
    private File writeThumbQuietly(File originFile) {
        try {
            String thumbName = com.culture.util.ImageUtil.thumbFileName(originFile.getName());
            if (thumbName == null) return null;
            File dir = originFile.getParentFile();
            int[] sizes = com.culture.util.ImageUtil.THUMB_SIZES;
            File legacy = new File(dir, thumbName);
            // 目标数组与 THUMB_SIZES 一一对应；480 档复用历史文件名（thumbFileName(name,480) 即 xxx_thumb.jpg）
            File[] targets = new File[sizes.length];
            for (int i = 0; i < sizes.length; i++) {
                String name = com.culture.util.ImageUtil.thumbFileName(originFile.getName(), sizes[i]);
                targets[i] = name == null ? null : new File(dir, name);
            }
            int done = com.culture.util.ImageUtil.writeThumbnails(originFile, targets, sizes);
            if (done <= 0) return null;
            return legacy.isFile() ? legacy : null;
        } catch (Exception e) {
            System.out.println("[上传] 缩略图生成失败（不影响上传）：" + e.getMessage());
            return null;
        }
    }

}
