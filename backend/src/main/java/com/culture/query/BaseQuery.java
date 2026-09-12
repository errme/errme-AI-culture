package com.culture.query;

import lombok.Data;


/**
 * 分页查询基类。
 *
 * <p>所有后台/前台列表接口的查询对象都继承它。分页参数由请求直接绑定，
 * 因此**必须做边界校验**：{@code pageSize} 可以被传成负数或极大值，
 * {@code page} 也可以缺省或为负数。</p>
 */
@Data
public class BaseQuery {

    /** 每页条数上限。防止 {@code ?pageSize=100000} 变成一次超大查询 / N+1 放大器。 */
    public static final int MAX_PAGE_SIZE = 200;

    /** offset 上限，避免深分页把数据库拖死（也避免 int 溢出） */
    public static final int MAX_OFFSET = 1_000_000;

    //开始位置
    private Integer offset = 0;
    //每页显示条数
    private Integer pageSize = 10;

    private Integer page;

    /**
     * 规范化分页参数并计算 offset，返回可直接用于 SQL 的偏移量。
     *
     * <p><b>为什么需要它：</b>原先各控制器里重复写着</p>
     * <pre>
     *   if (query.getPage() == null) query.setPage(1);
     *   if (query.getPageSize() == null) query.setPageSize(10);
     *   query.setOffset((query.getPage() - 1) * query.getPageSize());
     * </pre>
     * <p>这段代码有三个问题：</p>
     * <ol>
     *   <li>没有任何上界 —— {@code ?pageSize=-1} 会生成 {@code limit 0,-1}（SQL 语法错误 → 500）；
     *       {@code ?pageSize=100000} 则是一次超大查询；</li>
     *   <li>{@code (page - 1) * pageSize} 用 int 相乘，大数值会<b>溢出成负数</b>；</li>
     *   <li>部分调用点漏了 {@code page} 的空值判断，直接 {@code null - 1} 抛 NPE。</li>
     * </ol>
     * <p>注意：这里只做「安全钳制」，不改变正常请求的行为 ——
     * 合法范围内的 page/pageSize 计算结果与原来完全一致。</p>
     *
     * @return 规范化后的 offset
     */
    public int normalizePaging() {
        int safePage = (page == null || page < 1) ? 1 : page;
        int safeSize = (pageSize == null || pageSize < 1) ? 10 : pageSize;
        if (safeSize > MAX_PAGE_SIZE) {
            safeSize = MAX_PAGE_SIZE;
        }
        // 用 long 计算再钳制，避免 int 溢出为负数
        long off = (long) (safePage - 1) * safeSize;
        if (off < 0) {
            off = 0;
        }
        if (off > MAX_OFFSET) {
            off = MAX_OFFSET;
        }

        this.page = safePage;
        this.pageSize = safeSize;
        this.offset = (int) off;
        return this.offset;
    }
}
