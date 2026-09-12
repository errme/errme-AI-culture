package com.culture.entity;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 文化实体（对应新库 biz_culture 表）。
 * 属性名保持旧名，新库列(name/description/content/cover_url/view_count)
 * 在 Mapper 中映射回 cultureName/desc/info/fmUrl/view。
 */
@Data
public class Culture {

    private Long id;
    private String cultureName;
    private String address;
    /** 正文（新库 content 列） */
    /**
     * 富文本正文（DB 列 content，longtext）。
     * 列表接口已瘦身不再返回它（改为 infoSummary 摘要），这里加 NON_NULL 让列表响应不再出现
     * 一个恒为 null 的 info 字段；详情接口仍返回完整正文。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String info;
    /** 描述（新库 description 列） */
    private String desc;
    /** 封面图（新库 cover_url 列） */
    private String fmUrl;
    private Long categoryId;
    private Category category;

    private Long creatorId;
    private User user;

    private Date createTime;

    /**
     * 更新时间（新库 updated_at 列）。
     * 只有 SEO 专用查询（sitemap / rss / meta）会填充该字段，其它既有查询不查这一列，
     * 因此加上 NON_NULL 后不会出现在原有接口的 JSON 响应里（保持既有接口响应不变）。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Date updateTime;

    /** 浏览量（新库 view_count 列） */
    private Long view;

    /** 点赞数（新库 like_count 冗余列，迁移时已回填） */
    private Long likeCount;

    /**
     * 状态（新库 status 列，语义扩展：<b>0=草稿、1=已发布、2=定时待发布</b>）。
     *
     * <p>只有详情查询（{@code COLS}）会带出该列；列表查询（{@code LIST_COLS} / queryData）
     * 刻意不取它，因此这里的 NON_NULL 能保证：<b>列表接口的响应不会多出 status 字段</b>
     * （既有列表接口行为不变），只有详情接口会返回它（编辑回填 / 草稿箱需要）。</p>
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Integer status;

    /**
     * 定时发布时间（新库 publish_at 列，status=2 时必填）。
     *
     * <p>由 PublishScheduler 扫描：{@code status=2 and publish_at &lt;= now()} → 批量置为 1。
     * 时间格式固定为 {@code yyyy-MM-dd HH:mm:ss}（东八区，与 {@code serverTimezone=Asia/Shanghai}
     * 一致），避免 Jackson 默认按 UTC 解析导致存库时间偏移 8 小时。</p>
     *
     * <p>NON_NULL：未设置定时发布的内容该字段为 null，不出现在响应里。</p>
     */
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Date publishAt;

    /**
     * 是否置顶（新库 is_top 列，增量：内容推荐位/置顶，见 docs/sql/12_recommend.sql）。
     * 0=否（默认），1=是。前台首页热门（{@code queryHotAll}）与详情页推荐
     * （{@code findTop4Culture}）的排序第一位就是 {@code is_top desc}。
     *
     * <p>NON_NULL 的作用与 {@link #status} 相同：<b>只有查了这一列的查询才会返回该字段</b>。
     * 前台既有查询（COLS / LIST_COLS / queryData）都不取 is_top，因此这些接口的 JSON
     * 响应不会多出 isTop，既有接口响应保持不变；后台列表（queryAdminPage）会带出该列，
     * 供管理端渲染「置顶」状态（与 status/publishAt 的处理方式一致）。</p>
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Integer isTop;

    /**
     * 推荐位排序（新库 recommend_sort 列，增量：内容推荐位/置顶）。
     * <b>越小越靠前</b>，默认 0（存量数据全部为 0，因此并列后再按原排序字段排，
     * 前台顺序与改造前一致）。
     *
     * <p>与 {@link #isTop} 一样只在后台列表查询里带出，NON_NULL 保证其它接口响应不变。</p>
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Integer recommendSort;

    private double w;

    /**
     * 标签（非表字段，按需批量填充；为 null 时不出现在 JSON 中，保证既有接口响应不变）。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private List<Tag> tags;

    /**
     * 列表缩略图（非表字段，A3 新增）。
     * 列表接口会把 {@link #fmUrl} 也指向它（前端 coverUrl(fmUrl) 零改动即变小图），
     * 详情接口不填充该字段。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String coverThumb;

    /**
     * 封面原图（非表字段，A3 新增）。
     * 仅在列表接口填充：此时 {@link #fmUrl} 已被替换为缩略图，原封面值保留在这里，
     * 需要原图（预览/下载/SEO）时取该字段；详情接口的 fmUrl 本身就是原图，不填充该字段。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String coverOriginal;

    /**
     * 大档缩略图（非表字段，多档缩略图新增）。
     *
     * <p>命名规则 {@code xxx_thumb_1200.jpg}（长边 &lt;= {@code ImageUtil.THUMB_LARGE}=1200px），
     * 供<b>详情页大图 / 分享卡片（微信 og:image 等）</b>使用：比 480 档清晰，又比原图小得多。</p>
     *
     * <p>谁填充：</p>
     * <ul>
     *   <li>列表接口（{@code ThumbnailService#applyCoverThumb}）：与 {@link #coverThumb} 一起填充，
     *       {@link #fmUrl} 仍指向小档缩略图；</li>
     *   <li>前台详情（{@code CultureServiceImpl#findPublishedDetailById}）：<b>只填这一个字段</b>，
     *       {@link #fmUrl} 保持原图（详情页主图不能被降质）。</li>
     * </ul>
     *
     * <p>为 null 时不参与序列化（NON_NULL）：没有封面 / 外链封面 / 生成失败时不会出现该字段，
     * 且后台列表与管理端详情不会凭空多出它。它只是一个 URL 字符串，不携带大字段。</p>
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String coverThumbLarge;

    /**
     * 正文摘要（非表字段，列表瘦身新增）。
     *
     * <p>列表类查询（首页热门 / 文化列表 / 搜索 / 标签页 / 我的收藏 / 推荐位）SQL 里用
     * <code>LEFT(content, 300)</code> 取一段受限长度的摘要填充本字段，
     * <b>不再返回 longtext 全文</b> {@link #info}；完整正文只在详情接口返回。</p>
     *
     * <p>为 null 时不参与序列化（NON_NULL），所以详情接口的响应不会多出这个字段。</p>
     *
     * <p>注意：SQL 里取的是 content 原文的前 300 个字符，可能包含富文本标签片段，
     * 前端展示时请按纯文本转义/去标签处理，不要直接 v-html 渲染未截断完整的内容。</p>
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String infoSummary;
}
