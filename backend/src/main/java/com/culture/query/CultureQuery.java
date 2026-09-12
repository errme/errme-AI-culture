package com.culture.query;

import lombok.Data;

@Data
public class CultureQuery extends BaseQuery{

    private String cultureName;

    private Long categoryId;

    /**
     * 状态筛选（增量）：0=草稿/下架、1=已发布、2=定时待发布。
     *
     * <p>为 null 时<b>不参与 SQL 条件</b>，因此既有列表/导出的筛选行为完全不变；
     * 只有显式传 status 时才追加 {@code and u.status = ?}（草稿箱就是 status=0）。</p>
     *
     * <p><b>谁设置它</b>：后台入口 /api/admin/culture/list 不设置，直接采用请求参数
     * （?status=0 草稿箱、?status=2 定时待发布、不传=全部）；
     * 前台入口 ApiHomeController#cultureList 在参数绑定之后强制 setStatus(1)，
     * 因此前台列表（含总数 queryTotal）只含已发布内容，且 ?status=0 无法绕过。</p>
     */
    private Integer status;

    /**
     * 置顶筛选（增量：内容推荐位/置顶，见 docs/sql/12_recommend.sql）。
     *
     * <p>0=只看非置顶、1=只看置顶。<b>为 null 时不参与 SQL 条件</b>（whereSql 里用
     * {@code <if test="isTop != null">} 动态拼 {@code and u.is_top = #{isTop}}），
     * 因此既有列表/导出/统计的筛选行为完全不变；只有显式传 ?isTop=1 才会追加条件。</p>
     *
     * <p>前后台共用：/api/admin/culture/list?isTop=1 可用于运营筛选置顶内容；
     * 前台 /api/culture/list 也支持（前台入口只强制 status=1，不动 isTop）。</p>
     */
    private Integer isTop;

}
