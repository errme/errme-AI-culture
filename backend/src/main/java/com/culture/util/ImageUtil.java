package com.culture.util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

/**
 * 图片处理工具（仅用 JDK 自带 ImageIO，零第三方依赖）。
 *
 * <p>用途：上传的正文图片/封面/头像自动生成缩略图并适度压缩，避免前台加载几 MB 的原图。</p>
 *
 * <p><b>缩略图命名规则（可从原图地址推导）</b>：</p>
 * <pre>
 *   原图 xxx.png / xxx.jpeg / xxx.bmp（ImageIO 可解码的格式）
 *     -&gt; 同目录缩略图
 *          xxx_thumb.jpg        长边 &lt;= 480px（{@link #THUMB_MAX}，历史档，继续保留）
 *          xxx_thumb_320.jpg    长边 &lt;= 320px（{@link #THUMB_SMALL}，列表/卡片）
 *          xxx_thumb_640.jpg    长边 &lt;= 640px（{@link #THUMB_MEDIUM}，中档）
 *          xxx_thumb_1200.jpg   长边 &lt;= 1200px（{@link #THUMB_LARGE}，详情/分享）
 *   GIF
 *     -&gt; 不生成缩略图（ImageIO 重编码会丢动画），业务侧退回原图地址
 *   WebP 等 ImageIO 解码不了的格式
 *     -&gt; 生成返回 false，业务侧退回原图地址（将来接入 webp 解码库即可自动生效）
 * </pre>
 *
 * <p><b>多档缩略图为什么与老档并存</b>：{@code xxx_thumb.jpg}（480）已经有前端/模板在引用，
 * 改名或删除都会让老引用 404，因此多档是<b>新增</b>命名（{@code _thumb_<i>size</i>.jpg}），
 * 老档继续生成、继续可用。{@link #thumbUrl(String)} 仍等价于 480 档，
 * {@link #thumbUrl(String, int)} 是指定档位的通用入口。</p>
 *
 * <p><b>上传二次编码（{@link #reencode(File)}）</b>：上传的 jpg/jpeg/png 落盘后原地重新解码再编码一次，
 * 目的是丢弃 EXIF（可能含隐私信息）与文件尾部附加的恶意数据；GIF（可能动图）与 ImageIO
 * 解码不了的格式保持原文件，任何失败都回退为「原文件不动」，不影响上传主流程。
 * JPEG 的 EXIF Orientation（手机竖拍照片的方向标记）会在重编码前解析并<b>烘焙进像素</b>，
 * 避免「去掉 EXIF 后照片被转到侧面」。</p>
 *
 * <p>推导入口：{@link #thumbFileName(String)} / {@link #thumbFileName(String, int)}（文件名）、
 * {@link #thumbUrl(String)} / {@link #thumbUrl(String, int)}（URL，纯字符串规则）。
 * 需要「缩略图不存在时按需生成」时请用 {@code com.culture.service.ThumbnailService}，
 * 不要把目录解析/落盘逻辑散落到各个 Controller。</p>
 */
public final class ImageUtil {

    private ImageUtil() { }

    /** 缩略图长边默认尺寸（历史档，文件名不带尺寸后缀：xxx_thumb.jpg） */
    public static final int THUMB_MAX = 480;
    /** 小档（列表卡片，长边 320px） */
    public static final int THUMB_SMALL = 320;
    /** 中档（长边 640px） */
    public static final int THUMB_MEDIUM = 640;
    /** 大档（详情 / 分享卡片，长边 1200px） */
    public static final int THUMB_LARGE = 1200;
    /** 上传时一次性生成的档位（含历史 480 档，避免老引用失效） */
    public static final int[] THUMB_SIZES = {THUMB_SMALL, THUMB_MAX, THUMB_MEDIUM, THUMB_LARGE};
    /** 原图压缩阈值：宽度超过该值才重编码缩小 */
    public static final int COMPRESS_MAX_WIDTH = 1600;
    /** JPEG 重编码质量（压缩原图用） */
    private static final float JPEG_QUALITY = 0.82f;
    /** 缩略图 JPEG 质量（需求约定 0.8） */
    private static final float THUMB_JPEG_QUALITY = 0.8f;
    /**
     * 上传二次编码（{@link #reencode(File)}）的 JPEG 质量。
     * 刻意高于压缩档 0.82：二次编码本身就是一次有损编码，用 0.92 把画质损失压到肉眼不可见，
     * 体积仍远小于原始手机照片（EXIF 缩略图与厂商数据一并被丢弃）。
     */
    private static final float REENCODE_JPEG_QUALITY = 0.92f;

    /** 缩略图文件名后缀：xxx.png -> xxx_thumb.jpg（同目录） */
    public static final String THUMB_SUFFIX = "_thumb";
    /** 缩略图统一输出后缀（统一转 JPEG，体积最小；GIF 不生成缩略图） */
    public static final String THUMB_EXT = ".jpg";
    /** 多档缩略图文件名模式：{@code xxx_thumb_320}（去扩展名后判断，见 {@link #isThumb(String)}） */
    private static final java.util.regex.Pattern THUMB_SIZED_PATTERN =
            java.util.regex.Pattern.compile(".*" + THUMB_SUFFIX + "_\\d{1,5}");

    /**
     * 生成缩略图（长边 &lt;= {@link #THUMB_MAX}，JPEG 质量 0.8）。
     * 成功返回 true；任何异常都不抛出，仅返回 false，避免影响上传主流程。
     *
     * @param source 原图
     * @param target 缩略图目标文件（文件名请用 {@link #thumbFileName(String)} 推导，与原图同目录）
     */
    public static boolean writeThumbnail(File source, File target) {
        return writeThumbnail(source, target, THUMB_MAX);
    }

    /** 生成缩略图（指定长边上限）；成功返回 true，失败/不支持返回 false，不抛异常 */
    public static boolean writeThumbnail(File source, File target, int maxSize) {
        if (source == null || target == null || !source.isFile() || maxSize <= 0) return false;
        // GIF 保持原图不动
        if (isGif(source.getName())) return false;
        try {
            BufferedImage src = ImageIO.read(source);
            if (src == null) return false;
            return writeScaled(src, target, maxSize);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 一次解码、批量生成多档缩略图（上传时用）。
     *
     * <p>为什么单独开一个方法：上传一张 4000px 的手机照片要同时产出 320/480/640/1200 四档，
     * 若逐档调用 {@link #writeThumbnail(File, File, int)} 就会把同一张大图解码 4 次，
     * 上传耗时与内存峰值都翻几倍。这里只解码一次，再逐档缩放写出。</p>
     *
     * <p>容错与单档方法一致：任何一档失败只跳过该档（不影响其它档），整体不抛异常；
     * 返回<b>成功写出的档数</b>（0 表示原图不可解码 / 是 GIF / 全部失败）。</p>
     *
     * @param source  原图
     * @param targets 目标文件数组（通常用 {@link #thumbFileName(String, int)} 推导，与原图同目录）
     * @param sizes   与 targets 一一对应的长边上限（px）
     */
    public static int writeThumbnails(File source, File[] targets, int[] sizes) {
        if (source == null || !source.isFile() || targets == null || sizes == null
                || targets.length == 0 || targets.length != sizes.length) {
            return 0;
        }
        // GIF 保持原图不动（重编码会丢动画）
        if (isGif(source.getName())) return 0;
        BufferedImage src;
        try {
            src = ImageIO.read(source);
        } catch (Exception e) {
            return 0;
        }
        if (src == null) return 0;
        int done = 0;
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] == null || sizes[i] <= 0) continue;
            try {
                if (writeScaled(src, targets[i], sizes[i])) done++;
            } catch (Exception ignore) {
                // 单档失败不影响其它档
            }
        }
        return done;
    }

    /**
     * 把已解码的图片按长边上限缩放并写出到 target（先写临时文件再原子替换目标）。
     * 成功返回 true；不抛异常（任何失败返回 false）。调用方负责源文件的 GIF/存在性判断。
     */
    private static boolean writeScaled(BufferedImage src, File target, int maxSize) {
        File tmp = null;
        try {
            BufferedImage scaled = scale(src, maxSize, maxSize);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            // 先写唯一临时文件再替换目标：并发生成同一张缩略图时不会留下半截文件
            tmp = new File(target.getAbsolutePath() + "." + Long.toHexString(System.nanoTime()) + ".tmp");
            String format = extensionOf(target.getName());
            boolean ok = ("jpg".equals(format) || "jpeg".equals(format))
                    ? writeJpeg(scaled, tmp, THUMB_JPEG_QUALITY)
                    : ImageIO.write(scaled, format.isEmpty() ? "png" : format, tmp);
            if (!ok) return false;
            moveInto(tmp, target);
            tmp = null;
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    /** 临时文件替换目标文件；优先原子移动，平台不支持时退回覆盖移动 */
    private static void moveInto(File tmp, File target) throws IOException {
        try {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicUnsupported) {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 原图压缩：宽度超过 maxWidth 时等比缩小并重编码（仅 JPEG 重编码控制质量）。
     * 缩小成功返回 true；图片无需处理或处理失败返回 false。
     */
    public static boolean compress(File file, int maxWidth) {
        try {
            BufferedImage src = ImageIO.read(file);
            if (src == null) return false;
            if (src.getWidth() <= maxWidth) return false;
            String format = extensionOf(file.getName());
            // GIF 重编码会丢动画，不做压缩
            if ("gif".equals(format)) return false;
            BufferedImage scaled = scale(src, maxWidth, Integer.MAX_VALUE);

            // 关键：必须写到临时文件再替换原文件。
            // 直接用一个 ImageOutputStream 覆写原文件不会截断旧内容，
            // 结果是「图变小了、文件体积却没变」（尾部残留旧数据）。
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            boolean ok;
            if ("jpg".equals(format) || "jpeg".equals(format)) {
                ok = writeJpeg(scaled, tmp, JPEG_QUALITY);
            } else {
                ok = ImageIO.write(scaled, format.isEmpty() ? "png" : format, tmp);
            }
            if (!ok) {
                tmp.delete();
                return false;
            }
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 上传二次编码（安全加固） ====================

    /**
     * 上传图片二次编码（原地重写）：ImageIO 解码 → 重新编码写回同一个文件。
     *
     * <p><b>解决什么问题</b>：只靠「扩展名白名单 + 文件头嗅探」挡不住藏在图片里的数据 ——</p>
     * <ul>
     *   <li>JPEG/PNG 的元数据段（EXIF/XMP/ICC/PNG tEXt）可以塞进任意内容，EXIF 还常带
     *       拍摄地点等隐私信息（原实现只做压缩，小图不压缩时这些段原样留在盘上）；</li>
     *   <li>文件<b>尾部</b>可以附加任意字节（后面拼一段 HTML/脚本），部分解析器/旧浏览器会忽略；
     *       ImageIO 重新编码后整个文件被重写，尾部数据自然消失。</li>
     * </ul>
     *
     * <p><b>适用范围</b>：只处理 jpg/jpeg/png（需求约定的静态位图）。</p>
     * <ul>
     *   <li>GIF：可能带多帧动画，重编码会退化成静图 → 保持原文件不动；</li>
     *   <li>bmp/webp 等其它格式：不在本次二次编码范围内（webp 的 JDK ImageIO 本来就解不了）
     *       → 保持原文件不动；</li>
     *   <li>解码失败（文件损坏/伪装成图片）：直接返回 false，<b>原文件保持不动</b>，
     *       由调用方继续保存原文件，绝不让上传失败。</li>
     * </ul>
     *
     * <p><b>顺带做两件事</b>：</p>
     * <ol>
     *   <li>把 JPEG 的 EXIF Orientation 烘焙进像素（见 {@link #applyExifOrientation}）：
     *       去掉 EXIF 后手机竖拍照片不会「躺倒」；</li>
     *   <li>宽度超过 {@link #COMPRESS_MAX_WIDTH} 时同时等比缩小，于是调用方紧接着的
     *       {@link #compress(File, int)} 变成无操作 —— <b>避免对同一张图做两次有损编码</b>。</li>
     * </ol>
     *
     * <p>实现要点：写唯一临时文件 → 原子替换原文件。绝不能直接覆写：JPEG/PNG 用同一个
     * {@code ImageOutputStream} 覆写不会截断旧内容，会留下「图变小了但文件还是原来那么大」的
     * 尾部残留（与 {@link #compress} 同一个坑）。</p>
     *
     * @param file 已落盘的图片文件（原地重写）
     * @return true=已重新编码；false=不需要/不支持/失败（此时文件内容与调用前一致）
     */
    public static boolean reencode(File file) {
        if (file == null || !file.isFile() || file.length() <= 0) return false;
        String format = extensionOf(file.getName());
        // 只重编码 jpg/jpeg/png：GIF 保留动画、其它格式不在本次范围内
        boolean jpeg = "jpg".equals(format) || "jpeg".equals(format);
        if (!jpeg && !"png".equals(format)) return false;
        File tmp = null;
        try {
            BufferedImage src = ImageIO.read(file);
            if (src == null) return false;   // 解不开 -> 保留原文件
            // JPEG 的方向标记必须在丢掉 EXIF 之前应用到像素上
            BufferedImage oriented = jpeg ? applyExifOrientation(file, src) : src;
            // 与 compress 同阈值：一次编码同时完成「缩到 1600 以内」，后面 compress 自然成为空操作
            BufferedImage out = oriented.getWidth() > COMPRESS_MAX_WIDTH
                    ? scale(oriented, COMPRESS_MAX_WIDTH, Integer.MAX_VALUE)
                    : oriented;
            tmp = new File(file.getAbsolutePath() + "." + Long.toHexString(System.nanoTime()) + ".reenc.tmp");
            boolean ok = jpeg ? writeJpeg(out, tmp, REENCODE_JPEG_QUALITY)
                    : ImageIO.write(out, "png", tmp);
            if (!ok || !tmp.isFile() || tmp.length() <= 0) {
                return false;
            }
            moveInto(tmp, file);
            tmp = null;
            return true;
        } catch (Exception e) {
            // 任何异常都回退为「原文件不动」：上传不能被图片处理拖垮
            return false;
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    /**
     * 读取 JPEG 的 EXIF Orientation 并旋转/镜像像素，返回方向已正确的图片。
     * 解析失败、非 JPEG、orientation=1 或读不到时<b>原样返回</b>（等价于改造前只压缩的行为）。
     */
    private static BufferedImage applyExifOrientation(File jpegFile, BufferedImage src) {
        try {
            int orientation = readExifOrientation(jpegFile);
            return orientation > 1 && orientation <= 8 ? rotateByOrientation(src, orientation) : src;
        } catch (Exception e) {
            return src;
        }
    }

    /**
     * 从 JPEG 的 APP1(EXIF) 段里解析 Orientation(0x0112)。读不到返回 1（=不旋转）。
     * 全程严格校验边界，任何异常/越界都返回 1，宁可少旋转也不乱旋转。
     */
    private static int readExifOrientation(File jpegFile) {
        ImageReader reader = null;
        try (javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(jpegFile)) {
            if (in == null) return 1;
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
            if (!readers.hasNext()) return 1;
            reader = readers.next();
            // 关键：第三个参数 ignoreMetadata 必须是 false，
            // 否则 JPEGImageReader#getImageMetadata 直接返回 null，Orientation 就永远读不到。
            reader.setInput(in, true, false);
            javax.imageio.metadata.IIOMetadata metadata = reader.getImageMetadata(0);
            if (metadata == null) return 1;
            org.w3c.dom.Node root = metadata.getAsTree("javax_imageio_jpeg_image_1.0");
            org.w3c.dom.Node markerSequence = null;
            for (org.w3c.dom.Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                if ("markerSequence".equalsIgnoreCase(n.getNodeName())) {
                    markerSequence = n;
                    break;
                }
            }
            if (markerSequence == null) return 1;
            // APP1 段在 JDK 的树里是 markerSequence/unknown（MarkerTag=225=0xE1），
            // userObject 就是段数据（以 "Exif\0\0" 开头）
            for (org.w3c.dom.Node n = markerSequence.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!"unknown".equalsIgnoreCase(n.getNodeName())) continue;
                org.w3c.dom.Node tag = n.getAttributes() == null ? null
                        : n.getAttributes().getNamedItem("MarkerTag");
                if (tag == null || !"225".equals(tag.getNodeValue().trim())) continue;
                // getUserObject() 只有 JDK 的 IIOMetadataNode 才有（Node 接口上没有），必须先转型
                if (!(n instanceof javax.imageio.metadata.IIOMetadataNode)) continue;
                Object data = ((javax.imageio.metadata.IIOMetadataNode) n).getUserObject();
                if (data instanceof byte[]) {
                    int o = parseExifOrientation((byte[]) data);
                    if (o > 0) return o;
                }
            }
        } catch (Exception e) {
            return 1;
        } finally {
            if (reader != null) reader.dispose();
        }
        return 1;
    }

    /** 解析 APP1 段数据里的 Orientation；结构不符/越界返回 0（调用方继续找下一段） */
    private static int parseExifOrientation(byte[] data) {
        if (data == null || data.length < 14) return 0;
        // "Exif\0\0"
        if (data[0] != 'E' || data[1] != 'x' || data[2] != 'i' || data[3] != 'f'
                || data[4] != 0 || data[5] != 0) {
            return 0;
        }
        int tiff = 6;
        boolean little;
        if (data[tiff] == 'I' && data[tiff + 1] == 'I') {
            little = true;
        } else if (data[tiff] == 'M' && data[tiff + 1] == 'M') {
            little = false;
        } else {
            return 0;
        }
        if (readShort(data, tiff + 2, little) != 42) return 0;            // TIFF 魔数
        long ifdOffset = readInt(data, tiff + 4, little);
        long ifd = tiff + ifdOffset;
        if (ifd < 0 || ifd + 2 > data.length) return 0;
        int entries = readShort(data, (int) ifd, little);
        for (int i = 0; i < entries; i++) {
            int entry = (int) ifd + 2 + i * 12;
            if (entry + 12 > data.length) return 0;
            int tagId = readShort(data, entry, little);
            if (tagId != 0x0112) continue;                                 // Orientation
            int type = readShort(data, entry + 2, little);
            long count = readInt(data, entry + 4, little);
            if (type != 3 || count < 1) return 0;                          // 必须是 SHORT
            return readShort(data, entry + 8, little);                     // 值直接内联在 value 字段
        }
        return 0;
    }

    private static int readShort(byte[] b, int off, boolean little) {
        if (off < 0 || off + 2 > b.length) return 0;
        int b0 = b[off] & 0xFF;
        int b1 = b[off + 1] & 0xFF;
        return little ? (b0 | (b1 << 8)) : ((b0 << 8) | b1);
    }

    private static long readInt(byte[] b, int off, boolean little) {
        if (off < 0 || off + 4 > b.length) return 0;
        long b0 = b[off] & 0xFFL, b1 = b[off + 1] & 0xFFL, b2 = b[off + 2] & 0xFFL, b3 = b[off + 3] & 0xFFL;
        return little ? (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24))
                : ((b0 << 24) | (b1 << 16) | (b2 << 8) | b3);
    }

    /**
     * 按 EXIF Orientation（2..8）旋转/镜像像素。
     * 2=水平镜像、3=旋转180°、4=垂直镜像、5/7=镜像+旋转、6=顺时针90°、8=逆时针90°。
     * 5..8 需要交换宽高。
     */
    private static BufferedImage rotateByOrientation(BufferedImage src, int orientation) {
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        int nw = swap ? h : w;
        int nh = swap ? w : h;
        int type = src.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_RGB : src.getType();
        BufferedImage dst = new BufferedImage(nw, nh, type);
        java.awt.geom.AffineTransform t = new java.awt.geom.AffineTransform();
        switch (orientation) {
            case 2: t.translate(w, 0); t.scale(-1.0, 1.0); break;
            case 3: t.translate(w, h); t.rotate(Math.PI); break;
            case 4: t.translate(0, h); t.scale(1.0, -1.0); break;
            case 5: t.rotate(-Math.PI / 2); t.scale(-1.0, 1.0); break;
            case 6: t.translate(h, 0); t.rotate(Math.PI / 2); break;
            case 7: t.translate(h, 0); t.rotate(Math.PI / 2); t.scale(-1.0, 1.0); break;
            case 8: t.translate(0, w); t.rotate(3 * Math.PI / 2); break;
            default: return src;
        }
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /**
     * 按扩展名给出图片 MIME 类型（用于「直接写流」的图片响应，避免一律硬编码 image/jpeg）。
     * 不认识的扩展名返回 {@code application/octet-stream}：配合 {@code X-Content-Type-Options: nosniff}
     * 可以让历史遗留的 svg/html 之类文件以附件/纯字节流打开，而不是被浏览器当作可执行内容。
     */
    public static String contentTypeOf(String fileName) {
        String ext = extensionOf(fileName);
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
        if ("png".equals(ext)) return "image/png";
        if ("gif".equals(ext)) return "image/gif";
        if ("webp".equals(ext)) return "image/webp";
        if ("bmp".equals(ext)) return "image/bmp";
        if ("ico".equals(ext)) return "image/x-icon";
        if ("mp4".equals(ext)) return "video/mp4";
        if ("webm".equals(ext)) return "video/webm";
        if ("ogg".equals(ext) || "ogv".equals(ext)) return "video/ogg";
        if ("mov".equals(ext)) return "video/quicktime";
        if ("m4v".equals(ext)) return "video/x-m4v";
        return "application/octet-stream";
    }

    /** 是否图片文件名（按扩展名判断，用于给图片响应补 Content-Disposition: inline） */
    public static boolean isImageFileName(String fileName) {
        return contentTypeOf(fileName).startsWith("image/");
    }

    /**
     * 是否媒体文件名（图片或视频，用于静态资源目录的响应头加固）。
     */
    public static boolean isMediaFileName(String fileName) {
        String type = contentTypeOf(fileName);
        return type.startsWith("image/") || type.startsWith("video/");
    }
    /**
     * 由原图文件名推导缩略图文件名：{@code a/xxx.png -> xxx_thumb.jpg}（只取文件名，目录由调用方决定）。
     * 无法推导时返回 null：文件名为空、本身就是缩略图、GIF、没有扩展名。
     *
     * <p>等价于 {@code thumbFileName(fileName, THUMB_MAX)}，即<b>历史 480 档</b>命名，
     * 保持既有调用方（FileUpload / ThumbnailService）行为不变。多档请用
     * {@link #thumbFileName(String, int)}。</p>
     */
    public static String thumbFileName(String fileName) {
        if (fileName == null) return null;
        String name = fileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.isEmpty() || isThumb(name) || isGif(name)) return null;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return null;
        return name.substring(0, dot) + THUMB_SUFFIX + THUMB_EXT;
    }

    /**
     * 由原图文件名推导<b>指定档位</b>的缩略图文件名：
     * {@code xxx.png + 320 -> xxx_thumb_320.jpg}；{@code size <= 0} 或 {@code size == THUMB_MAX}
     * 时退回历史命名 {@code xxx_thumb.jpg}（480 档已经存在于磁盘上，直接复用老文件，
     * 不再产生一份内容相同的 {@code _thumb_480.jpg}）。
     *
     * <p>无法推导时返回 null：文件名为空、本身就是缩略图（任意档位，见 {@link #isThumb}）、
     * GIF、没有扩展名。</p>
     *
     * @param size 长边上限（px），推荐用 {@link #THUMB_SMALL}/{@link #THUMB_MEDIUM}/{@link #THUMB_LARGE}
     */
    public static String thumbFileName(String fileName, int size) {
        if (size <= 0 || size == THUMB_MAX) return thumbFileName(fileName);
        String legacy = thumbFileName(fileName);
        if (legacy == null) return null;
        int dot = legacy.lastIndexOf('.');
        return legacy.substring(0, dot) + "_" + size + THUMB_EXT;
    }

    /**
     * 由原图 URL 推导缩略图 URL（纯字符串规则，不访问磁盘、不判断文件是否存在）：
     * {@code /upload/media/image/202401/xxx.png -> /upload/media/image/202401/xxx_thumb.jpg}，
     * 裸文件名 {@code xxx.png -> xxx_thumb.jpg}（同风格返回，便于前端自动补 URL 前缀）。
     * 无法推导（外链/已是缩略图/GIF/无扩展名）时返回 null，调用方必须退回原图地址。
     *
     * <p>本方法是 480 档的兼容入口，等价于 {@link #thumbUrl(String, int)} 传 {@link #THUMB_MAX}。</p>
     */
    public static String thumbUrl(String url) {
        return thumbUrl(url, THUMB_MAX);
    }

    /**
     * 由原图 URL 推导<b>指定档位</b>的缩略图 URL（纯字符串规则，不访问磁盘）：
     * {@code /upload/media/image/202401/xxx.png + 1200 -> .../xxx_thumb_1200.jpg}。
     *
     * <p>规则与 {@link #thumbFileName(String, int)} 一致：{@code size <= 0} 或 {@code size == THUMB_MAX}
     * 时返回历史命名（{@code xxx_thumb.jpg}），所以 {@code thumbUrl(url)} 与
     * {@code thumbUrl(url, 480)} 结果完全相同。</p>
     *
     * <p>无法推导（null/空串/外链/已是任意档缩略图/GIF/无扩展名）时返回 null，
     * 调用方必须退回原图地址（或按档位回退到旧档，见 {@code ThumbnailService}）。</p>
     */
    public static String thumbUrl(String url, int size) {
        if (url == null) return null;
        String value = url.trim();
        if (value.isEmpty() || isExternalUrl(value)) return null;
        String name = thumbFileName(value, size);
        if (name == null) return null;
        int slash = value.lastIndexOf('/');
        return slash < 0 ? name : value.substring(0, slash + 1) + name;
    }

    /**
     * 是否已经是缩略图文件名，避免二次推导成 {@code xxx_thumb_thumb.jpg} /
     * {@code xxx_thumb_320_thumb_640.jpg}。
     *
     * <p>认两种命名（大小写不敏感）：</p>
     * <ul>
     *   <li>历史档：{@code xxx_thumb.jpg}（去掉扩展名后以 {@code _thumb} 结尾）；</li>
     *   <li>多档：{@code xxx_thumb_320.jpg}（去掉扩展名后匹配 {@code _thumb_<数字>}）。</li>
     * </ul>
     */
    public static boolean isThumb(String fileName) {
        if (fileName == null) return false;
        String name = fileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith(THUMB_SUFFIX)) return true;
        return THUMB_SIZED_PATTERN.matcher(name).matches();
    }

    /** 是否 GIF（GIF 保持原图，不生成缩略图） */
    public static boolean isGif(String fileName) {
        return "gif".equals(extensionOf(fileName == null ? "" : fileName));
    }

    /** 是否站外地址（http/https/协议相对），站外图片不做任何缩略图处理 */
    public static boolean isExternalUrl(String url) {
        if (url == null) return false;
        String value = url.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("http://") || value.startsWith("https://") || value.startsWith("//");
    }

    /** 取文件扩展名（小写，不含点） */
    public static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 计算缩略图文件名：xxx.jpg -&gt; xxx_thumb.jpg（保留原扩展名）。
     *
     * @deprecated 保留仅为兼容旧调用方；统一规则请用 {@link #thumbFileName(String)}
     *             （后者统一输出 _thumb.jpg，GIF 返回 null）。
     */
    @Deprecated
    public static String thumbName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return fileName + "_thumb";
        return fileName.substring(0, dot) + "_thumb" + fileName.substring(dot);
    }

    private static BufferedImage scale(BufferedImage src, int maxW, int maxH) {
        int w = src.getWidth();
        int h = src.getHeight();
        double ratio = Math.min(maxW / (double) w, maxH / (double) h);
        if (ratio >= 1.0) return src;
        int nw = Math.max(1, (int) Math.round(w * ratio));
        int nh = Math.max(1, (int) Math.round(h * ratio));
        int type = src.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_RGB : src.getType();
        BufferedImage dst = new BufferedImage(nw, nh, type);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private static boolean writeJpeg(BufferedImage image, File target, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) return ImageIO.write(image, "jpg", target);
        ImageWriter writer = writers.next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            // JPEG 不支持透明通道，先转 RGB
            BufferedImage rgb = image;
            if (image.getType() != BufferedImage.TYPE_INT_RGB) {
                rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.drawImage(image, 0, 0, java.awt.Color.WHITE, null);
                g.dispose();
            }
            writer.write(null, new IIOImage(rgb, null, null), param);
            return true;
        } finally {
            writer.dispose();
        }
    }
}
