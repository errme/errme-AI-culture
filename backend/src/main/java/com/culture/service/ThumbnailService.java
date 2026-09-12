package com.culture.service;

import com.culture.entity.Culture;
import com.culture.entity.Sentence;
import com.culture.entity.User;

import java.util.List;

/**
 * 缩略图服务（A3：列表缩略图派生）。
 *
 * <p><b>命名规则</b>（可从原图地址推导，详见 {@link com.culture.util.ImageUtil#thumbFileName(String)}）：</p>
 * <pre>
 *   原图 xxx.png -&gt; 同目录 xxx_thumb.jpg（长边 &lt;= 480px，JPEG 质量 0.8，历史档）
 *                -&gt; 同目录 xxx_thumb_320.jpg / xxx_thumb_640.jpg / xxx_thumb_1200.jpg（多档增量）
 *   GIF          -&gt; 不生成缩略图，直接返回原图地址
 *   WebP 等 ImageIO 解码不了的格式 / 站外链 -&gt; 直接返回原图地址
 * </pre>
 *
 * <p><b>回退策略</b>（历史图片没有缩略图）：</p>
 * <ol>
 *   <li>缩略图已存在 -&gt; 直接返回缩略图地址；</li>
 *   <li>缩略图不存在 -&gt; <b>按需生成并落盘</b>后返回缩略图地址（第一次访问时生成一次）；</li>
 *   <li>原图不存在 / 生成失败 / 外链 / GIF / 已是缩略图 -&gt; 返回原图地址，绝不返回坏链。</li>
 * </ol>
 *
 * <p>列表接口通过 {@link #applyCoverThumb(Culture)} / {@link #applyCoverThumb(List)} 使用：
 * {@code fmUrl} 被替换为缩略图（前端 coverUrl(fmUrl) 零改动即变小图），
 * 原图保留在 {@code coverOriginal}，缩略图同时写入 {@code coverThumb}。
 * 详情接口（{@code /api/culture/detail}）与富文本正文不做任何替换，仍是原图。</p>
 */
public interface ThumbnailService {

    /**
     * 文化封面缩略图地址。
     *
     * @param fmUrl 封面原值（biz_culture.cover_url）：裸文件名（落到 culture.upload.path）
     *              或 /upload/media/** 绝对路径
     */
    String coverThumbUrl(String fmUrl);

    /**
     * 头像缩略图地址。
     *
     * @param headImg 头像原值（sys_user.avatar）：裸文件名（落到 avatar.upload.path）
     */
    String avatarThumbUrl(String headImg);

    /** 富文本正文媒体（/upload/media/**）缩略图地址 */
    String mediaThumbUrl(String url);

    /** 列表用：fmUrl -&gt; 缩略图，原图保留在 coverOriginal，缩略图写入 coverThumb */
    void applyCoverThumb(Culture culture);

    /** 列表用：批量处理（内部逐条调用 {@link #applyCoverThumb(Culture)}） */
    void applyCoverThumb(List<Culture> cultures);

    /** 列表用：headImg -&gt; 头像缩略图，原图保留在 headImgOriginal */
    void applyAvatarThumb(List<User> users);

    /** 列表用：句子作者头像 createImg -&gt; 缩略图，原图保留在 createImgOriginal */
    void applySentenceAvatarThumb(List<Sentence> sentences);

    // ===================== 多档缩略图（增量） =====================
    // 档位常量见 ImageUtil：THUMB_SMALL=320 / THUMB_MAX=480(历史档) / THUMB_MEDIUM=640 / THUMB_LARGE=1200。
    // 命名：xxx_thumb.jpg（480，历史档）与 xxx_thumb_<size>.jpg（新增档），两者并存。

    /**
     * 指定档位的封面缩略图地址（按需生成）。
     *
     * <p>与 {@link #coverThumbUrl(String)} 的唯一区别是可以指定长边档位：
     * {@code coverThumbUrl(url, ImageUtil.THUMB_LARGE)} 得到 {@code xxx_thumb_1200.jpg}。
     * {@code size <= 0} 或 {@code size == ImageUtil.THUMB_MAX(480)} 时退回历史档，
     * 此时与 {@link #coverThumbUrl(String)} 完全等价。</p>
     *
     * <p><b>回退链</b>（绝不返回坏链）：目标档已存在 → 返回；不存在 → 按需生成；
     * 生成失败 / 原图不在磁盘 → 回退到历史 480 档（若该档可用）→ 再不行回退原图地址。</p>
     *
     * @param fmUrl 封面原值（裸文件名或 /upload/media/** 路径）
     * @param size  长边上限（px）
     */
    String coverThumbUrl(String fmUrl, int size);

    /** 大档（{@link com.culture.util.ImageUtil#THUMB_LARGE}=1200）封面缩略图：详情页大图 / 分享卡片用 */
    String coverThumbLargeUrl(String fmUrl);

    /** 指定档位的头像缩略图（按需生成，回退链同 {@link #coverThumbUrl(String, int)}） */
    String avatarThumbUrl(String headImg, int size);

    /** 指定档位的正文媒体（/upload/media/**）缩略图（按需生成，回退链同上） */
    String mediaThumbUrl(String url, int size);

    /**
     * 详情 / 分享用：<b>只填</b> {@code coverThumbLarge}（1200 档），
     * <b>不动 fmUrl</b>（详情页主图仍是原图，不能被降质），也不填 coverThumb/coverOriginal。
     * 没有封面 / 外链封面 / 生成失败时不填（NON_NULL → 响应里不出现该字段，也不会出现坏链）。
     */
    void applyCoverThumbLarge(Culture culture);
}
