package com.culture.api;

import com.culture.auth.service.BusinessException;
import com.culture.entity.*;
import com.culture.query.*;
import com.culture.service.*;
import com.culture.util.CsvUtil;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 后台管理 API（需 JWT，且要求"管理员"角色）：
 * 统计 / 菜单 / 文化 / 分类 / 公告 / 句子 / 用户 / 角色。
 * 供 Vue 后台（vue-frontend/src/views/admin）使用。
 *
 * <p>「删除可撤销」增量端点（全部要求管理员，body 支持 {id} 或 {ids:[...]}，返回 data { count }）：</p>
 * <pre>
 * POST /api/admin/culture/restore       POST /api/admin/category/restore
 * POST /api/admin/announcement/restore  POST /api/admin/sentence/restore
 * POST /api/admin/user/restore
 * GET  /api/admin/culture/detail?id=    后台详情（含完整正文 info，编辑回填用；不累加浏览量）
 * </pre>
 *
 * <p>「内容版本历史」增量端点（管理员，返回 {code,message,data}）：</p>
 * <pre>
 * GET  /api/admin/culture/versions?id=&amp;page=1&amp;pageSize=20  版本列表（不含正文全文，data:{total,rows})
 * GET  /api/admin/culture/version?id=                        单条版本详情（含完整 content）
 * POST /api/admin/culture/rollback   body {versionId}         回滚（回滚前先给当前内容写快照）
 * </pre>
 *
 * <p>「定时发布 + 草稿箱」增量端点：</p>
 * <pre>
 * POST /api/admin/culture/save          status=0/1/2；status=2 时 publishAt 必填且必须晚于当前时间
 * GET  /api/admin/culture/list?status=  列表支持按状态过滤（0=草稿 1=已发布 2=定时待发布）
 * POST /api/admin/culture/batch-status  body {ids:[...], status} → data {count}
 * </pre>
 *
 * <p>「内容推荐位 / 置顶」增量端点（见 docs/sql/12_recommend.sql）：</p>
 * <pre>
 * POST /api/admin/culture/top             body {ids:[...], isTop:0|1} → data {count}
 * POST /api/admin/culture/recommend-sort  body {id, recommendSort}   → data {count}（越小越靠前）
 * GET  /api/admin/culture/list?isTop=1    列表支持按置顶过滤（不传则不过滤，行为不变）
 * </pre>
 */
@RestController
@RequestMapping("/api/admin")
public class ApiAdminController {

    @Autowired
    private CultureService cultureService;
    @Autowired
    private CategoryService categoryService;
    @Autowired
    private AnnouncementService announcementService;
    @Autowired
    private SentenceService sentenceService;
    @Autowired
    private UserService userService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private MenuService menuService;

    /** 后台菜单按用户缓存（版本号失效，见 CacheService.KEY_MENU_EPOCH） */
    @Autowired
    private com.culture.service.CacheService cacheService;

    /** 列表缩略图派生（A3）：用户列表头像改用缩略图 */
    @Autowired
    private ThumbnailService thumbnailService;

    /** CSV 导出用：按批 id 一次性取回标签，避免 N+1 */
    @Autowired
    private TagService tagService;

    /** 内容版本历史（保存即留痕 / 可对比 / 可回滚） */
    @Autowired
    private CultureVersionService cultureVersionService;

    /** CSV 导出每批条数：分批查询 + 流式写出，内存里始终只有一批数据 */
    private static final int EXPORT_BATCH_SIZE = 500;

    // ============ 通用 ============

    /** 校验当前用户是否管理员 */
    private boolean isAdmin(Long userId) {
        for (Role r : roleService.listRoleByUserId(userId)) {
            if ("管理员".equals(r.getName())) return true;
        }
        return false;
    }

    private Long currentUserId(HttpServletRequest request) {
        return (Long) request.getAttribute("loginUserId");
    }

    /** 当前登录用户信息 + 是否管理员 */
    @GetMapping("/me")
    public ApiResult<Map<String, Object>> me(HttpServletRequest request) {
        Long uid = currentUserId(request);
        User user = userService.findById(uid);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user", user);
        data.put("admin", isAdmin(uid));
        return ApiResult.ok(data);
    }

    /** 仪表盘统计 */
    @GetMapping("/stats")
    public ApiResult<Map<String, Object>> stats(HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalView", cultureService.queryTotalViewNum());
        data.put("totalCulture", cultureService.queryTotalCultureNum());
        data.put("totalUser", cultureService.queryTotalUserRegNum());
        data.put("totalLike", cultureService.queryTotalScNum());
        return ApiResult.ok(data);
    }

    /**
     * 侧边栏菜单（按用户角色）。
     *
     * <p>走 Redis 按用户缓存（key 带全局版本号，见 {@link com.culture.service.CacheService#KEY_MENU_EPOCH}）：
     * 角色/权限/用户角色授权一变就 INCR 版本号，所有用户的旧缓存立刻不可达，不会漏失效。
     * 菜单只影响导航展示，接口鉴权另有独立判断，因此缓存滞后不会造成越权。</p>
     */
    @GetMapping("/menus")
    public ApiResult<List<Menu>> menus(HttpServletRequest request) {
        Long uid = currentUserId(request);
        return ApiResult.ok(cacheService.getOrLoadMenu(
                uid == null ? -1L : uid,
                () -> menuService.findAll(uid),
                new com.fasterxml.jackson.core.type.TypeReference<List<Menu>>() {
                }));
    }

    // ============ 文化管理 ============

    /**
     * 文化列表分页。
     * 增量：CultureQuery 新增 status 字段，因此 <code>?status=0</code> 就是草稿箱、
     * <code>?status=2</code> 是定时待发布列表；不传 status 时筛选行为与改造前完全一致。
     *
     * <p>增量（草稿箱体验）：改走后台专用的 {@code listpageForAdmin}（SQL = queryAdminPage），
     * 数据行比前台列表额外返回 <b>status</b> 与 <b>publishAt</b>，供列表「状态」列渲染
     * 草稿 / 已发布 / 定时徽章（并显示定时发布时间）。
     * 筛选、排序、分页、总数与原来完全一致；前台 /api/culture/list 仍走 queryData，
     * 响应字段不变。</p>
     */
    @GetMapping("/culture/list")
    public ApiResult<PageList> cultureList(CultureQuery query) {
        if (query.getPage() == null) query.setPage(1);
        if (query.getPageSize() == null) query.setPageSize(10);
        query.setOffset((query.getPage() - 1) * query.getPageSize());
        return ApiResult.ok(cultureService.listpageForAdmin(query));
    }

    /**
     * 新增/编辑（有 id 则编辑）。
     *
     * <p>增量：支持草稿与定时发布（status / publishAt），规则如下：</p>
     * <ul>
     *   <li>status 取值 0=草稿、1=已发布、2=定时待发布；<b>不传就保持原值</b>
     *       （新增不传 → 用表默认值 1=已发布，与改造前一致）；</li>
     *   <li>status=2 时 publishAt 必填，且必须<b>晚于当前时间</b>，否则返回 400 业务错误，
     *       不会写库（避免「定时」时间已过导致内容立刻被调度器发布）；</li>
     *   <li>publishAt 的 JSON 格式固定为 <code>yyyy-MM-dd HH:mm:ss</code>（东八区）。</li>
     * </ul>
     *
     * <p>编辑保存时会先写一条「修改前」的版本快照（见 CultureServiceImpl.editSaveCulture）。</p>
     */
    @PostMapping("/culture/save")
    public ApiResult<Void> cultureSave(@RequestBody Culture culture, HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            // 状态/定时发布时间校验（不传 status 时完全按老逻辑走，既有调用行为不变）
            if (culture.getStatus() != null) {
                int status = culture.getStatus();
                if (status != 0 && status != 1 && status != 2) {
                    return ApiResult.error("状态不合法：0=草稿、1=已发布、2=定时待发布");
                }
                if (status == 2) {
                    if (culture.getPublishAt() == null) {
                        return ApiResult.error("定时发布必须填写发布时间 publishAt");
                    }
                    if (!culture.getPublishAt().after(new Date())) {
                        return ApiResult.error("定时发布时间必须晚于当前时间");
                    }
                }
            }
            if (culture.getId() == null) {
                culture.setCreatorId(currentUserId(request));
                cultureService.addCulture(culture);
            } else {
                // 带上操作人：版本快照会记录 operator_id / operator_name
                cultureService.editSaveCulture(culture, currentUserId(request), null);
            }
            return ApiResult.ok(null);
        } catch (Exception e) {
            return ApiResult.error("保存失败：" + e.getMessage());
        }
    }

    @PostMapping("/culture/delete")
    public ApiResult<Void> cultureDelete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            cultureService.deleteCulture(Long.valueOf(String.valueOf(body.get("id"))));
            return ApiResult.ok(null);
        } catch (Exception e) {
            return ApiResult.error("删除失败");
        }
    }

    // ---------- 批量操作 / CSV 导出（增量追加） ----------

    /**
     * 批量逻辑删除：body { ids: [1,2,3] }，返回 data { count: 实际处理条数 }。
     * ids 为空或超过 500 条 → 400 业务错误（不会 500）；日志由 OperationLogInterceptor 自动记录
     * （POST /api/admin/**），targetId 由下面的 oplogTarget 主动回填。
     */
    @PostMapping("/culture/batch-delete")
    public ApiResult<Map<String, Object>> cultureBatchDelete(@RequestBody Map<String, Object> body,
                                                             HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            List<Long> ids = toLongList(body.get("ids"));
            request.setAttribute("oplogTargetId", oplogTarget(ids));
            int count = cultureService.batchDelete(ids);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", count);
            return ApiResult.ok(data);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("批量删除失败：" + e.getMessage());
        }
    }

    /**
     * 恢复被逻辑删除的文化：body <code>{id}</code> 或 <code>{ids:[1,2,3]}</code>，
     * 返回 data { count: 实际恢复条数 }。
     *
     * <p>与删除严格对称：删除只置 deleted=1，恢复只把 deleted 改回 0；
     * 只恢复「已逻辑删除」的行（SQL 带 deleted=1），对正常记录调用 count 为 0。
     * 标签关联在删除时是物理删除的，恢复文化不会恢复标签绑定。</p>
     *
     * <p>权限与 /culture/delete 保持一致：必须是管理员。</p>
     */
    @PostMapping("/culture/restore")
    public ApiResult<Map<String, Object>> cultureRestore(@RequestBody Map<String, Object> body,
                                                         HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        return restore(body, request, "恢复失败", new RestoreExecutor() {
            @Override
            public int execute(Long id, List<Long> ids) {
                return ids != null ? cultureService.restoreBatch(ids) : cultureService.restore(id);
            }
        });
    }

    /**
     * 后台文化详情（编辑回填专用）：body 无，GET ?id=。
     *
     * <p>为什么需要它：文化列表接口已瘦身，不再返回 longtext 正文 info
     * （只返回 infoSummary 摘要），而编辑弹窗需要完整正文回填。
     * 这里返回完整 Culture（含 info），并且<b>不像前台 /api/culture/detail 那样累加浏览量</b>，
     * 后台编辑不会污染统计数据。权限：管理员。</p>
     */
    @GetMapping("/culture/detail")
    public ApiResult<Culture> cultureDetail(@RequestParam(value = "id", required = false) Long id,
                                            HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        if (id == null) return ApiResult.error("参数错误：缺少文化 id");
        Culture culture = cultureService.findDetailById(id);
        if (culture == null) return ApiResult.error(404, "内容不存在或已被删除");
        return ApiResult.ok(culture);
    }

    /**
     * 批量修改分类：body { ids: [1,2,3], categoryId: 3 }，返回 data { count: 实际处理条数 }。
     * categoryId 不存在（或已逻辑删除）时返回 400 业务错误。
     */
    @PostMapping("/culture/batch-category")
    public ApiResult<Map<String, Object>> cultureBatchCategory(@RequestBody Map<String, Object> body,
                                                               HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            List<Long> ids = toLongList(body.get("ids"));
            Long categoryId = toLong(body.get("categoryId"));
            request.setAttribute("oplogTargetId", oplogTarget(ids));
            int count = cultureService.batchUpdateCategory(ids, categoryId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", count);
            return ApiResult.ok(data);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("批量修改分类失败：" + e.getMessage());
        }
    }

    /**
     * 批量修改状态（增量）：body <code>{ ids: [1,2,3], status: 1 }</code>，
     * 返回 data <code>{ count: 实际处理条数 }</code>。
     *
     * <p>status 只允许 0=草稿、1=已发布、2=定时待发布，其它值返回 400 业务错误；
     * ids 为空或超过 500 条同样是 400。批量只改 status 一列，不动 publish_at
     * （定时发布的具体时间通过 /culture/save 设置）。</p>
     */
    @PostMapping("/culture/batch-status")
    public ApiResult<Map<String, Object>> cultureBatchStatus(@RequestBody Map<String, Object> body,
                                                             HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            List<Long> ids = toLongList(body.get("ids"));
            Integer status = toInt(body.get("status"));
            request.setAttribute("oplogTargetId", oplogTarget(ids));
            int count = cultureService.batchUpdateStatus(ids, status);
            return countResult(count);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("批量修改状态失败：" + e.getMessage());
        }
    }

    // ---------- 内容推荐位 / 置顶（增量追加，见 docs/sql/12_recommend.sql） ----------

    /**
     * 批量置顶 / 取消置顶：POST /api/admin/culture/top，
     * body <code>{ "ids": [1,2,3], "isTop": 1 }</code> → data <code>{ "count": 3 }</code>。
     *
     * <p>请求示例：</p>
     * <pre>
     *   curl -X POST http://localhost:8081/api/admin/culture/top \
     *        -H 'Content-Type: application/json' -H 'Authorization: Bearer &lt;adminToken&gt;' \
     *        -d '{"ids":[12,15],"isTop":1}'      // 置顶
     *        -d '{"ids":[12],"isTop":0}'         // 取消置顶
     *   成功：{"code":0,"message":"success","data":{"count":2}}
     *   参数不合法（isTop 非 0/1、ids 为空或 >500 条）：{"code":400,...}
     * </pre>
     *
     * <p>生效位置：前台首页热门（/api/home → hotCultures）与详情页推荐
     * （findRecommendCultures 的热门兜底）排序首位 {@code is_top desc}；后台列表排序不变。
     * 只改 is_top 一列，<b>不动 recommend_sort</b>，所以取消置顶后再置顶，
     * 之前设置的推荐顺序仍在。权限：管理员。</p>
     */
    @PostMapping("/culture/top")
    public ApiResult<Map<String, Object>> cultureTop(@RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            List<Long> ids = toLongList(body.get("ids"));
            Integer isTop = toInt(body.get("isTop"));
            request.setAttribute("oplogTargetId", oplogTarget(ids));
            int count = cultureService.batchUpdateTop(ids, isTop);
            return countResult(count);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("批量置顶失败：" + e.getMessage());
        }
    }

    /**
     * 调整单条内容的推荐位顺序：POST /api/admin/culture/recommend-sort，
     * body <code>{ "id": 12, "recommendSort": 1 }</code> → data <code>{ "count": 1 }</code>。
     *
     * <p>请求示例：</p>
     * <pre>
     *   curl -X POST http://localhost:8081/api/admin/culture/recommend-sort \
     *        -H 'Content-Type: application/json' -H 'Authorization: Bearer &lt;adminToken&gt;' \
     *        -d '{"id":12,"recommendSort":1}'
     *   成功：{"code":0,"message":"success","data":{"count":1}}
     *   内容不存在 / 已被逻辑删除：{"code":404,"message":"内容不存在或已被删除","data":null}
     *   缺少 id 或 recommendSort：{"code":400,...}
     * </pre>
     *
     * <p>语义：<b>数值越小越靠前</b>，允许 0 与负数（负数可直接「插到最前」，不必重排全表）；
     * 只改 recommend_sort 一列，不动 is_top。排序同样作用在
     * {@code is_top desc, recommend_sort asc, view_count desc} 这条前台排序链上。权限：管理员。</p>
     */
    @PostMapping("/culture/recommend-sort")
    public ApiResult<Map<String, Object>> cultureRecommendSort(@RequestBody Map<String, Object> body,
                                                               HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            Long id = toLong(body.get("id"));
            Integer recommendSort = toInt(body.get("recommendSort"));
            if (id != null) request.setAttribute("oplogTargetId", String.valueOf(id));
            int count = cultureService.updateRecommendSort(id, recommendSort);
            if (count == 0) {
                // SQL 自带 deleted=0：影响行数 0 = id 不存在或已被逻辑删除
                return ApiResult.error(404, "内容不存在或已被删除");
            }
            return countResult(count);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("调整推荐顺序失败：" + e.getMessage());
        }
    }

    // ---------- 内容版本历史（增量追加） ----------

    /**
     * 版本列表：GET /api/admin/culture/versions?id=1&amp;page=1&amp;pageSize=20
     * → data <code>{ total, rows:[{id, cultureId, name, operatorName, createTime, contentLength}] }</code>。
     *
     * <p><b>不返回 content 全文</b>（列表只给正文长度 contentLength），
     * 需要全文做对比/预览时再调 /culture/version?id=。</p>
     */
    @GetMapping("/culture/versions")
    public ApiResult<PageList> cultureVersions(@RequestParam(value = "id", required = false) Long id,
                                               @RequestParam(value = "page", required = false) Integer page,
                                               @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                               HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            return ApiResult.ok(cultureVersionService.listVersions(id, page, pageSize));
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("查询版本历史失败：" + e.getMessage());
        }
    }

    /**
     * 单条版本详情：GET /api/admin/culture/version?id=100
     * → data 为完整版本对象（含 content 全文），用于版本对比与预览。
     * 版本不存在返回 404。
     */
    @GetMapping("/culture/version")
    public ApiResult<CultureVersion> cultureVersion(@RequestParam(value = "id", required = false) Long id,
                                                    HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        if (id == null) return ApiResult.error("参数错误：缺少版本 id");
        CultureVersion version = cultureVersionService.findVersion(id);
        if (version == null) return ApiResult.error(404, "版本不存在");
        return ApiResult.ok(version);
    }

    /**
     * 回滚到指定版本：POST /api/admin/culture/rollback，body <code>{ versionId: 100 }</code>
     * → data <code>{ count: 实际更新条数 }</code>。
     *
     * <p><b>回滚前会先给「当前内容」写一条快照</b>（不做分钟去重），
     * 因此回滚本身也是可逆的：回滚后如果发现回滚错了，可以用刚生成的那条版本再滚回来。
     * 若回滚前快照写入失败，接口返回 400 且<b>不会执行回滚</b>（宁可失败也不可逆）。</p>
     */
    @PostMapping("/culture/rollback")
    public ApiResult<Map<String, Object>> cultureRollback(@RequestBody Map<String, Object> body,
                                                          HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            Long versionId = toLong(body.get("versionId"));
            if (versionId == null) {
                throw new BusinessException("参数错误：缺少版本 id（versionId）");
            }
            request.setAttribute("oplogTargetId", String.valueOf(versionId));
            int count = cultureVersionService.rollback(versionId, currentUserId(request), null);
            return countResult(count);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("回滚失败：" + e.getMessage());
        }
    }

    /**
     * 导出 CSV：GET /api/admin/culture/export?cultureName=&amp;categoryId=&amp;keyword=&amp;status=
     * （筛选参数与后台列表一致；keyword 作为 cultureName 的别名兼容）。
     *
     * <p>实现要点：</p>
     * <ul>
     *   <li>UTF-8 BOM 开头 + text/csv; charset=UTF-8，Excel 直接打开不乱码；</li>
     *   <li>按 {@link #EXPORT_BATCH_SIZE} 条分批查询，边查边写 OutputStream，不把全表读进内存；</li>
     *   <li>字段按 RFC4180 转义并对 = + - @ 开头的内容做公式注入防护（见 CsvUtil）；</li>
     *   <li>不导出富文本正文（longtext），只导标题/分类/标签等摘要级字段 + 状态与时间。</li>
     * </ul>
     */
    @GetMapping("/culture/export")
    public void cultureExport(CultureQuery query,
                              @RequestParam(value = "keyword", required = false) String keyword,
                              HttpServletRequest request,
                              HttpServletResponse response) throws Exception {
        // 权限：导出属于后台数据操作，非管理员直接回 JSON（此时还没写任何 CSV 内容）
        if (!isAdmin(currentUserId(request))) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"无权限\",\"data\":null}");
            return;
        }

        // 筛选条件与列表保持一致；keyword 是 cultureName 的别名（列表接口用的是 cultureName）
        if (query == null) query = new CultureQuery();
        if ((query.getCultureName() == null || query.getCultureName().trim().isEmpty())
                && keyword != null && !keyword.trim().isEmpty()) {
            query.setCultureName(keyword.trim());
        }

        // 文件名保持纯 ASCII，避免 Content-Disposition 中文需要额外编码
        String fileName = "cultures_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".csv";
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        // 允许前端 JS 读取文件名（跨域下载场景）
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");

        OutputStream out = response.getOutputStream();
        out.write(CsvUtil.BOM);                 // UTF-8 BOM：Excel 识别 UTF-8 的关键
        Writer writer = new OutputStreamWriter(out, "UTF-8");
        writer.write(CsvUtil.row("ID", "标题", "作者", "分类", "标签", "状态", "创建时间", "更新时间"));

        int offset = 0;
        try {
            while (true) {
                List<Map<String, Object>> rows = cultureService.queryExportBatch(query, offset, EXPORT_BATCH_SIZE);
                if (rows == null || rows.isEmpty()) break;

                // 本批标签一次取回（避免逐条查询），再在内存里与本批行对齐
                List<Long> ids = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Long id = CsvUtil.toLong(row.get("id"));
                    if (id != null) ids.add(id);
                }
                Map<String, List<Tag>> tagMap = tagService.tagsOfCultures(ids);

                for (Map<String, Object> row : rows) {
                    Long id = CsvUtil.toLong(row.get("id"));
                    writer.write(CsvUtil.row(
                            id == null ? "" : String.valueOf(id),
                            str(row.get("cultureName")),
                            str(row.get("authorName")),
                            str(row.get("categoryName")),
                            tagNames(tagMap.get(String.valueOf(id))),
                            cultureStatusText(row.get("status")),
                            CsvUtil.formatDateTime(row.get("createTime")),
                            CsvUtil.formatDateTime(row.get("updateTime"))));
                }
                writer.flush();                 // 每批落盘，保证内存占用与数据量无关
                offset += rows.size();
                if (rows.size() < EXPORT_BATCH_SIZE) break;
            }
        } catch (Exception e) {
            // 响应已开始输出，无法再改成 JSON 错误；打印日志后中断，避免把错误页混进 CSV
            System.out.println("[culture-export] 导出中断：" + e.getMessage());
            e.printStackTrace();
        } finally {
            writer.flush();
        }
    }

    /** 标签名逗号拼接（导出「标签」列）；无标签返回空串 */
    private String tagNames(List<Tag> tags) {
        if (tags == null || tags.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Tag t : tags) {
            if (t == null || t.getName() == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(t.getName());
        }
        return sb.toString();
    }

    /**
     * 文化状态文案（CSV 导出列）：0=草稿、1=已发布、2=定时待发布，其它值原样输出。
     *
     * <p>说明：本次把 status 语义从「0=下架 / 1=上架」扩展为「0=草稿 / 1=已发布 / 2=定时待发布」，
     * 因此这里同步把 0 的文案改成「草稿」并补上 2；<b>只影响导出文件里的状态文字，不涉及任何数据</b>
     * （库里现有数据全部是 1=已发布，导出内容不变）。</p>
     */
    private String cultureStatusText(Object status) {
        if (status == null) return "";
        String v = String.valueOf(status);
        if ("1".equals(v)) return "已发布";
        if ("0".equals(v)) return "草稿";
        if ("2".equals(v)) return "定时待发布";
        return v;
    }

    /** null 安全的字符串化（导出列用） */
    private String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** body 里的 id 数组容错解析（支持数字/字符串；非法值与 null 直接忽略） */
    @SuppressWarnings("unchecked")
    private List<Long> toLongList(Object v) {
        if (!(v instanceof List)) return null;
        List<Long> ids = new ArrayList<>();
        for (Object o : (List<Object>) v) {
            Long id = toLong(o);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** body 里的整数容错解析（支持数字/字符串，如 status 传 1 或 "1"；非法值与 null 返回 null） */
    private Integer toInt(Object v) {
        if (v == null) return null;
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 操作日志目标：id 太多时只记前 5 个（拦截器还会再截断到 64 字符） */
    private String oplogTarget(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("n=").append(ids.size()).append(':');
        for (int i = 0; i < ids.size() && i < 5; i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    // ---------- 删除可撤销：恢复（增量追加） ----------

    /** 单条/批量恢复的执行入口（各实体的 service 方法不同，用它可以只写一份 body 解析逻辑） */
    private interface RestoreExecutor {
        /**
         * @param id  单条恢复时的 id（批量时为 null）
         * @param ids 批量恢复时的 id 列表（单条时为 null）
         * @return 实际影响行数
         */
        int execute(Long id, List<Long> ids);
    }

    /**
     * 恢复端点公共流程：body 支持 <code>{id}</code>（单条）或 <code>{ids:[...]}</code>（批量，优先）。
     * <ul>
     *   <li>两者都没传（或传了但全部非法）→ BusinessException → 400 业务错误，不会 500；</li>
     *   <li>ids 超过 500 条 → Service 内的 normalizeIds 抛 BusinessException → 400；</li>
     *   <li>返回信封 {code,message,data}，data 里是 {count: 实际影响行数}。</li>
     * </ul>
     */
    private ApiResult<Map<String, Object>> restore(Map<String, Object> body, HttpServletRequest request,
                                                   String failMessage, RestoreExecutor executor) {
        try {
            Long id = toLong(body.get("id"));
            List<Long> ids = toLongList(body.get("ids"));
            // 批量优先：ids 传了且解析出至少一个合法 id 时走批量
            if (ids != null && !ids.isEmpty()) {
                request.setAttribute("oplogTargetId", oplogTarget(ids));
                return countResult(executor.execute(null, ids));
            }
            if (id != null) {
                request.setAttribute("oplogTargetId", String.valueOf(id));
                return countResult(executor.execute(id, null));
            }
            throw new BusinessException("参数错误：请传入 id（单条）或 ids（批量）");
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error(failMessage + "：" + e.getMessage());
        }
    }

    /** 统一 { count: n } 响应体 */
    private ApiResult<Map<String, Object>> countResult(int count) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", count);
        return ApiResult.ok(data);
    }

    // ============ 分类管理 ============

    @GetMapping("/category/list")
    public ApiResult<PageList> categoryList(CategoryQuery query) {
        if (query.getPage() == null) query.setPage(1);
        if (query.getPageSize() == null) query.setPageSize(10);
        query.setOffset((query.getPage() - 1) * query.getPageSize());
        return ApiResult.ok(categoryService.listpage(query));
    }

    @PostMapping("/category/save")
    public ApiResult<Void> categorySave(@RequestBody Category category, HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            if (category.getId() == null) categoryService.addSave(category);
            else categoryService.editSaveCategory(category);
            return ApiResult.ok(null);
        } catch (Exception e) {
            return ApiResult.error("保存失败");
        }
    }

    @PostMapping("/category/delete")
    public ApiResult<Void> categoryDelete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        categoryService.deleteCategory(Long.valueOf(String.valueOf(body.get("id"))));
        return ApiResult.ok(null);
    }

    /**
     * 恢复被逻辑删除的分类：body {id} 或 {ids:[...]} → data { count }。
     * 权限与 /category/delete 一致（管理员）；空参数/超 500 条返回 400 业务错误。
     */
    @PostMapping("/category/restore")
    public ApiResult<Map<String, Object>> categoryRestore(@RequestBody Map<String, Object> body,
                                                          HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        return restore(body, request, "恢复失败", new RestoreExecutor() {
            @Override
            public int execute(Long id, List<Long> ids) {
                return ids != null ? categoryService.restoreBatch(ids) : categoryService.restore(id);
            }
        });
    }

    // ============ 公告管理 ============

    @GetMapping("/announcement/list")
    public ApiResult<PageList> announcementList(AnnouncementQuery query) {
        if (query.getPage() == null) query.setPage(1);
        if (query.getPageSize() == null) query.setPageSize(10);
        query.setOffset((query.getPage() - 1) * query.getPageSize());
        return ApiResult.ok(announcementService.listpage(query));
    }

    @PostMapping("/announcement/save")
    public ApiResult<Void> announcementSave(@RequestBody Announcement a, HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            if (a.getId() == null) announcementService.addAnnouncement(a);
            else announcementService.editAnnouncement(a);
            return ApiResult.ok(null);
        } catch (Exception e) {
            return ApiResult.error("保存失败");
        }
    }

    @PostMapping("/announcement/delete")
    public ApiResult<Void> announcementDelete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        announcementService.deleteAnnouncement(Long.valueOf(String.valueOf(body.get("id"))));
        return ApiResult.ok(null);
    }

    /**
     * 恢复被逻辑删除的公告：body {id} 或 {ids:[...]} → data { count }。
     * 权限与 /announcement/delete 一致（管理员）；空参数/超 500 条返回 400 业务错误。
     */
    @PostMapping("/announcement/restore")
    public ApiResult<Map<String, Object>> announcementRestore(@RequestBody Map<String, Object> body,
                                                              HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        return restore(body, request, "恢复失败", new RestoreExecutor() {
            @Override
            public int execute(Long id, List<Long> ids) {
                return ids != null ? announcementService.restoreBatch(ids) : announcementService.restore(id);
            }
        });
    }

    // ============ 句子管理 ============

    @GetMapping("/sentence/list")
    public ApiResult<PageList> sentenceList(SentenceQuery query) {
        if (query.getPage() == null) query.setPage(1);
        if (query.getPageSize() == null) query.setPageSize(10);
        query.setOffset((query.getPage() - 1) * query.getPageSize());
        return ApiResult.ok(sentenceService.listpage(query));
    }

    @PostMapping("/sentence/save")
    public ApiResult<Void> sentenceSave(@RequestBody Sentence s, HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            if (s.getId() == null) {
                User u = userService.findById(currentUserId(request));
                s.setCreateId(u.getId());
                s.setCreateName(u.getUsername());
                sentenceService.addSentence(s);
            } else {
                sentenceService.editSaveSentence(s);
            }
            return ApiResult.ok(null);
        } catch (Exception e) {
            return ApiResult.error("保存失败");
        }
    }

    @PostMapping("/sentence/delete")
    public ApiResult<Void> sentenceDelete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        sentenceService.deleteSentence(Long.valueOf(String.valueOf(body.get("id"))));
        return ApiResult.ok(null);
    }

    /**
     * 恢复被逻辑删除的句子：body {id} 或 {ids:[...]} → data { count }。
     * 权限与 /sentence/delete 一致（管理员）；空参数/超 500 条返回 400 业务错误。
     */
    @PostMapping("/sentence/restore")
    public ApiResult<Map<String, Object>> sentenceRestore(@RequestBody Map<String, Object> body,
                                                          HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        return restore(body, request, "恢复失败", new RestoreExecutor() {
            @Override
            public int execute(Long id, List<Long> ids) {
                return ids != null ? sentenceService.restoreBatch(ids) : sentenceService.restore(id);
            }
        });
    }

    // ============ 用户管理 ============

    @GetMapping("/user/list")
    public ApiResult<PageList> userList(UserQuery query) {
        if (query.getPage() == null) query.setPage(1);
        if (query.getPageSize() == null) query.setPageSize(10);
        query.setOffset((query.getPage() - 1) * query.getPageSize());
        PageList pageList = userService.listpage(query);
        // A3：用户列表 35px 头像改用缩略图（headImg -> xxx_thumb.jpg，原图在 headImgOriginal）；
        //     个人资料（/admin/me、/api/user/center）不经过这里，仍是原图
        java.util.List<User> rows = new java.util.ArrayList<>();
        if (pageList.getRows() != null) {
            for (Object row : pageList.getRows()) {
                if (row instanceof User) rows.add((User) row);
            }
        }
        thumbnailService.applyAvatarThumb(rows);
        return ApiResult.ok(pageList);
    }

    @PostMapping("/user/save")
    public ApiResult<Void> userSave(@RequestBody User user, HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            if (user.getId() == null) {
                userService.addUser(user);
            } else {
                userService.editSaveUser(user);
            }
            return ApiResult.ok(null);
        } catch (Exception e) {
            return ApiResult.error("保存失败：" + e.getMessage());
        }
    }

    @PostMapping("/user/delete")
    public ApiResult<Void> userDelete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        userService.deleteUser(Long.valueOf(String.valueOf(body.get("id"))));
        return ApiResult.ok(null);
    }

    /**
     * 恢复被逻辑删除的用户：body {id} 或 {ids:[...]} → data { count }。
     * 只把 deleted 改回 0，<b>status 等字段保持原值</b>（不会把禁用账号意外启用）。
     * 权限与 /user/delete 一致（管理员）；空参数/超 500 条返回 400 业务错误。
     */
    @PostMapping("/user/restore")
    public ApiResult<Map<String, Object>> userRestore(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        return restore(body, request, "恢复失败", new RestoreExecutor() {
            @Override
            public int execute(Long id, List<Long> ids) {
                return ids != null ? userService.restoreBatch(ids) : userService.restore(id);
            }
        });
    }

    /** 给用户分配角色 */
    @PostMapping("/user/role")
    public ApiResult<Void> userRole(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        String userId = String.valueOf(body.get("userId"));
        List<String> roleIds = (List<String>) body.get("roleIds");
        roleService.addUserRole(userId, roleIds);
        return ApiResult.ok(null);
    }

    // ============ 角色 ============

    @GetMapping("/roles")
    public ApiResult<List<Role>> roles() {
        return ApiResult.ok(roleService.queryAll());
    }

    /** 用户已有角色ID */
    @GetMapping("/user/roles")
    public ApiResult<List<Role>> userRoles(@RequestParam Long userId) {
        return ApiResult.ok(roleService.listRoleByUserId(userId));
    }
}
