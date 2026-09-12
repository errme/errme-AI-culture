package com.culture.service.impl;

import com.culture.entity.Culture;
import com.culture.entity.Sentence;
import com.culture.entity.User;
import com.culture.service.ThumbnailService;
import com.culture.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

/**
 * 缩略图服务实现（零第三方依赖，只用 JDK ImageIO + 项目配置的上传目录）。
 *
 * <p>地址解析：</p>
 * <pre>
 *   /upload/media/image/202401/xxx.png  -&gt; {editor.upload.path}/image/202401/xxx.png
 *   /showFmImg/xxx.png                  -&gt; {culture.upload.path}/xxx.png
 *   /showimage/xxx.png                  -&gt; {avatar.upload.path}/xxx.png
 *   裸文件名 xxx.png                     -&gt; 按调用场景落到 封面/头像 上传目录
 * </pre>
 *
 * <p>所有异常都在内部消化并回退原图地址，列表接口不会因为缩略图问题报错。</p>
 */
@Service
public class ThumbnailServiceImpl implements ThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailServiceImpl.class);

    /** 正文媒体访问前缀（与 MyPicConfig 的 /upload/media/** 映射一致） */
    private static final String MEDIA_PREFIX = "/upload/media/";
    /** 封面访问前缀（FileUpload#showFmImg） */
    private static final String COVER_PREFIX = "/showFmImg/";
    /** 头像访问前缀（FileUpload#showphoto） */
    private static final String AVATAR_PREFIX = "/showimage/";

    /** 图片来源：决定裸文件名/相对地址落在哪个上传目录 */
    private static final int KIND_COVER = 0;
    private static final int KIND_AVATAR = 1;
    private static final int KIND_MEDIA = 2;

    /** 按需生成的串行锁：历史图片首次被访问时可能并发触发，串行化避免重复解码大图 */
    private static final Object GENERATE_LOCK = new Object();

    /**
     * 生成失败的路径（负缓存）：坏图/格式不支持时不必每次列表请求都重新解码。
     * 只记内存、重启即清空；上限 {@link #FAILED_MAX} 条，避免无限增长。
     */
    private static final java.util.Set<String> FAILED = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final int FAILED_MAX = 512;

    @Value("${culture.upload.path}")
    private String cultureUploadPath;

    @Value("${avatar.upload.path}")
    private String avatarUploadPath;

    @Value("${editor.upload.path}")
    private String editorUploadPath;

    @Override
    public String coverThumbUrl(String fmUrl) {
        return resolve(fmUrl, KIND_COVER, ImageUtil.THUMB_MAX);
    }

    @Override
    public String coverThumbUrl(String fmUrl, int size) {
        return resolve(fmUrl, KIND_COVER, size);
    }

    @Override
    public String coverThumbLargeUrl(String fmUrl) {
        return resolve(fmUrl, KIND_COVER, ImageUtil.THUMB_LARGE);
    }

    @Override
    public String avatarThumbUrl(String headImg) {
        return resolve(headImg, KIND_AVATAR, ImageUtil.THUMB_MAX);
    }

    @Override
    public String avatarThumbUrl(String headImg, int size) {
        return resolve(headImg, KIND_AVATAR, size);
    }

    @Override
    public String mediaThumbUrl(String url) {
        return resolve(url, KIND_MEDIA, ImageUtil.THUMB_MAX);
    }

    @Override
    public String mediaThumbUrl(String url, int size) {
        return resolve(url, KIND_MEDIA, size);
    }

    @Override
    public void applyCoverThumb(Culture culture) {
        if (culture == null) return;
        // 幂等：已被处理过（原图已留档）就不再处理，避免把缩略图当原图再推导一次
        if (culture.getCoverOriginal() != null) return;
        String origin = culture.getFmUrl();
        if (origin == null || origin.trim().isEmpty()) return;
        // 顺序很重要：两个地址都必须从「原图 origin」推导，必须在覆盖 fmUrl 之前算完
        String thumb = coverThumbUrl(origin);
        String thumbLarge = coverThumbLargeUrl(origin);
        culture.setCoverOriginal(origin);
        culture.setCoverThumb(thumb);
        culture.setCoverThumbLarge(thumbLarge);
        // 列表接口：fmUrl 指向缩略图，前端 coverUrl(fmUrl) 无需改动；详情接口不调用本方法
        culture.setFmUrl(thumb);
    }

    /**
     * 详情 / 分享用：只填大档（1200），fmUrl 保持原图不动。
     * 生成失败时 {@link #resolve} 内部会回退到历史 480 档 / 原图地址，这里不需要再兜底。
     */
    @Override
    public void applyCoverThumbLarge(Culture culture) {
        if (culture == null) return;
        if (culture.getCoverThumbLarge() != null) return;   // 幂等
        String origin = culture.getFmUrl();
        if (origin == null || origin.trim().isEmpty()) return;
        culture.setCoverThumbLarge(coverThumbLargeUrl(origin));
    }

    @Override
    public void applyCoverThumb(List<Culture> cultures) {
        if (cultures == null || cultures.isEmpty()) return;
        for (Culture culture : cultures) {
            applyCoverThumb(culture);
        }
    }

    @Override
    public void applyAvatarThumb(List<User> users) {
        if (users == null || users.isEmpty()) return;
        for (User user : users) {
            if (user == null || user.getHeadImgOriginal() != null) continue;
            String origin = user.getHeadImg();
            if (origin == null || origin.trim().isEmpty()) continue;
            String thumb = avatarThumbUrl(origin);
            user.setHeadImgOriginal(origin);
            user.setHeadImgThumb(thumb);
            user.setHeadImg(thumb);
        }
    }

    @Override
    public void applySentenceAvatarThumb(List<Sentence> sentences) {
        if (sentences == null || sentences.isEmpty()) return;
        for (Sentence sentence : sentences) {
            if (sentence == null || sentence.getCreateImgOriginal() != null) continue;
            String origin = sentence.getCreateImg();
            if (origin == null || origin.trim().isEmpty()) continue;
            String thumb = avatarThumbUrl(origin);
            sentence.setCreateImgOriginal(origin);
            sentence.setCreateImgThumb(thumb);
            sentence.setCreateImg(thumb);
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 核心：原图地址 -&gt; 指定档位缩略图地址（按需生成 + 多级回退）。
     *
     * <p>处理顺序：</p>
     * <ol>
     *   <li>外链 / 已经是缩略图（任意档位）/ GIF / 推导不出文件名 -&gt; 原样返回；</li>
     *   <li>原图不在磁盘上 -&gt; 原样返回（历史数据只存了 URL 的情况）；</li>
     *   <li>目标档已存在且非空 -&gt; 直接返回目标档地址；</li>
     *   <li>目标档缺失 -&gt; 按需生成一次（同目标路径负缓存，坏图不会每次请求都重试）；</li>
     *   <li><b>生成失败</b> -&gt; 回退历史 480 档（可用则返回它的地址）-&gt; 再不行回退原图地址。
     *       绝不返回一个磁盘上不存在的文件地址（坏链）。</li>
     * </ol>
     *
     * @param size 长边上限；{@code <=0} 或 {@link ImageUtil#THUMB_MAX} 时等于历史 480 档
     */
    private String resolve(String origin, int kind, int size) {
        if (origin == null) return null;
        String url = origin.trim();
        if (url.isEmpty()) return origin;
        // 站外图片 / 已经是缩略图 / GIF：保持原样
        if (ImageUtil.isExternalUrl(url) || ImageUtil.isThumb(url) || ImageUtil.isGif(url)) return origin;

        String thumbName = ImageUtil.thumbFileName(url, size);
        if (thumbName == null) return origin;

        File originFile = resolveFile(url, kind);
        if (originFile == null || !originFile.isFile()) return origin;   // 原图不在磁盘上 -> 回退

        File thumbFile = new File(originFile.getParentFile(), thumbName);
        if (!usable(thumbFile)) {
            if (!FAILED.contains(thumbFile.getAbsolutePath())) {
                synchronized (GENERATE_LOCK) {
                    if (!usable(thumbFile) && !ImageUtil.writeThumbnail(originFile, thumbFile, size <= 0 ? ImageUtil.THUMB_MAX : size)) {
                        log.warn("[thumb] 缩略图生成失败，回退旧档/原图：{}", originFile.getAbsolutePath());
                        if (FAILED.size() < FAILED_MAX) FAILED.add(thumbFile.getAbsolutePath());
                    } else if (usable(thumbFile)) {
                        log.info("[thumb] 按需生成缩略图：{} -> {}", originFile.getName(), thumbFile.getName());
                    }
                }
            }
            // 目标档仍不可用（生成失败或命中负缓存）-> 回退链：历史 480 档 -> 原图
            if (!usable(thumbFile)) {
                return fallback(url, originFile, size);
            }
        }
        return buildThumbUrl(url, thumbName);
    }

    /**
     * 目标档不可用时的回退链：
     * 历史 480 档（{@code xxx_thumb.jpg}）可用则返回它的地址，否则返回原图地址。
     * 只会返回「磁盘上真实存在」的地址或原图地址，因此不会出现坏链。
     */
    private String fallback(String url, File originFile, int size) {
        // 请求的就是历史档（size<=0 / 480）时没有更小的档可退，直接回原图
        if (size > 0 && size != ImageUtil.THUMB_MAX) {
            String legacyName = ImageUtil.thumbFileName(url);
            if (legacyName != null) {
                File legacy = new File(originFile.getParentFile(), legacyName);
                if (usable(legacy)) return buildThumbUrl(url, legacyName);
            }
        }
        return url;
    }

    /** 文件可用（存在且非空） */
    private static boolean usable(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }

    /** 把访问地址还原成磁盘文件；路径非法/越界时返回 null（调用方回退原图） */
    private File resolveFile(String url, int kind) {
        if (url.startsWith(MEDIA_PREFIX)) {
            File root = editorRoot();
            String rel = url.substring(MEDIA_PREFIX.length());
            return inside(root, new File(root, rel.replace('/', File.separatorChar)));
        }
        if (url.startsWith(COVER_PREFIX)) {
            File root = new File(cultureUploadPath);
            return inside(root, new File(root, url.substring(COVER_PREFIX.length())));
        }
        if (url.startsWith(AVATAR_PREFIX)) {
            File root = new File(avatarUploadPath);
            return inside(root, new File(root, url.substring(AVATAR_PREFIX.length())));
        }
        // 裸文件名或其它站内相对路径：按调用场景（封面/头像/正文媒体）落到对应上传目录
        File root = kind == KIND_AVATAR ? new File(avatarUploadPath)
                : kind == KIND_MEDIA ? editorRoot()
                : new File(cultureUploadPath);
        String rel = url.startsWith("/") ? url.substring(1) : url;
        return inside(root, new File(root, rel.replace('/', File.separatorChar)));
    }

    /** 目标文件必须位于 root 目录内，防止 cover_url 里出现 ../ 造成越权读取 */
    private static File inside(File root, File file) {
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (filePath.startsWith(rootPath + File.separator)) return file;
        } catch (Exception e) {
            // 忽略：交由调用方回退原图
        }
        return null;
    }

    /** 缩略图访问地址：保持与原值同一风格（绝对路径前缀原样保留，裸文件名仍返回裸文件名） */
    private static String buildThumbUrl(String url, String thumbName) {
        int slash = url.lastIndexOf('/');
        return slash < 0 ? thumbName : url.substring(0, slash + 1) + thumbName;
    }

    /** 正文媒体根目录（补上结尾分隔符，避免拼出 upload/mediaimage） */
    private File editorRoot() {
        String path = editorUploadPath == null ? "" : editorUploadPath;
        return path.endsWith("/") || path.endsWith("\\") ? new File(path) : new File(path + File.separator);
    }
}
