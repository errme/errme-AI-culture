package com.culture.api;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Role;
import com.culture.entity.Tag;
import com.culture.service.RoleService;
import com.culture.service.TagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台标签管理 API（/api/admin/tag，需后台令牌 + 管理员角色）。
 *
 * <pre>
 * GET  /api/admin/tag/list         标签列表
 * POST /api/admin/tag/save         新增/编辑（有 id 编辑）
 * POST /api/admin/tag/delete       body {id}
 * POST /api/admin/tag/batch-delete body {ids:[1,2,3]} -> { count }（增量追加）
 * POST /api/admin/tag/restore      body {id} 或 {ids:[1,2,3]} -> { count }（增量追加，删除可撤销）
 * POST /api/admin/tag/merge        body {sourceId, targetId}    -> { moved, merged, sourceId, targetId }（标签合并）
 * POST /api/admin/tag/rename       body {id, name}              -> { id, name }（只改名，不动 slug/sort）
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/tag")
public class ApiAdminTagController {

    @Autowired
    private TagService tagService;
    @Autowired
    private RoleService roleService;

    /** 校验当前用户是否管理员（与 ApiAdminController 保持一致） */
    private boolean isAdmin(Long userId) {
        if (userId == null) return false;
        for (Role r : roleService.listRoleByUserId(userId)) {
            if ("管理员".equals(r.getName())) return true;
        }
        return false;
    }

    /** 标签列表 */
    @GetMapping("/list")
    public ApiResult<List<Tag>> list(HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        return ApiResult.ok(tagService.queryAll());
    }

    /** 新增/编辑 */
    @PostMapping("/save")
    public ApiResult<Map<String, Object>> save(@RequestBody Tag tag, HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        try {
            Map<String, Object> data = tagService.save(tag);
            // 供操作日志拦截器提取目标 id（拦截器读不到 JSON body，这里主动告知）
            request.setAttribute("oplogTargetId", data.get("id"));
            return ApiResult.ok(data);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("保存失败：" + e.getMessage());
        }
    }

    /** 删除（同时解除文化关联） */
    @PostMapping("/delete")
    public ApiResult<Void> delete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        try {
            Long id = toLong(body.get("id"));
            request.setAttribute("oplogTargetId", id);
            tagService.delete(id);
            return ApiResult.ok(null);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("删除失败：" + e.getMessage());
        }
    }

    /**
     * 批量删除标签（逻辑删除 + 解除文化关联）：body { ids: [1,2,3] }。
     * 返回 data { count: 实际删除条数 }；ids 为空或超过 500 条返回 400 业务错误。
     */
    @PostMapping("/batch-delete")
    public ApiResult<Map<String, Object>> batchDelete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        try {
            List<Long> ids = toLongList(body.get("ids"));
            request.setAttribute("oplogTargetId", oplogTarget(ids));
            int count = tagService.batchDelete(ids);
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
     * 恢复被逻辑删除的标签：body <code>{id}</code> 或 <code>{ids:[1,2,3]}</code>，
     * 返回 data { count: 实际恢复条数 }。参数为空或超过 500 条返回 400 业务错误。
     *
     * <p>注意：删除标签时 biz_culture_tag 的文化关联是<b>物理删除</b>的，库里已无痕迹，
     * 因此恢复标签只让标签本体重新可见（内容数为 0），<b>不恢复原关联</b>，
     * 需要管理员在文化编辑页重新绑定。</p>
     */
    @PostMapping("/restore")
    public ApiResult<Map<String, Object>> restore(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        try {
            Long id = toLong(body.get("id"));
            List<Long> ids = toLongList(body.get("ids"));
            int count;
            // 批量优先：ids 传了且解析出至少一个合法 id 时走批量
            if (ids != null && !ids.isEmpty()) {
                request.setAttribute("oplogTargetId", oplogTarget(ids));
                count = tagService.restoreBatch(ids);
            } else if (id != null) {
                request.setAttribute("oplogTargetId", String.valueOf(id));
                count = tagService.restore(id);
            } else {
                throw new BusinessException("参数错误：请传入 id（单条）或 ids（批量）");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", count);
            return ApiResult.ok(data);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("恢复失败：" + e.getMessage());
        }
    }

    // ==================== 标签合并 / 改名（增量追加） ====================

    /**
     * 标签合并：body { sourceId: 3, targetId: 7 }，把源标签的关联改挂到目标标签后逻辑删除源标签。
     * 返回 data { moved, merged, sourceId, targetId }：moved=实际改挂的关联数，
     * merged=因「文化已挂目标标签」而直接合并掉的关联数。
     * 两个标签不存在/已删除/相同都返回 400；整个过程在一个事务里，失败整体回滚。
     */
    @PostMapping("/merge")
    public ApiResult<Map<String, Object>> merge(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        try {
            Long sourceId = toLong(body.get("sourceId"));
            Long targetId = toLong(body.get("targetId"));
            Map<String, Object> data = tagService.merge(sourceId, targetId);
            // 拦截器读不到 JSON body，主动告知操作日志目标
            request.setAttribute("oplogTargetId", sourceId + "->" + targetId);
            return ApiResult.ok(data);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("合并失败：" + e.getMessage());
        }
    }

    /**
     * 标签改名：body { id: 3, name: "新名字" }，只改 name，slug / sort 保持不变。
     * 名称为空或重名返回 400；返回 data { id, name }。
     */
    @PostMapping("/rename")
    public ApiResult<Map<String, Object>> rename(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        try {
            Long id = toLong(body.get("id"));
            Object name = body.get("name");
            Map<String, Object> data = tagService.rename(id, name == null ? null : String.valueOf(name));
            request.setAttribute("oplogTargetId", id);
            return ApiResult.ok(data);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("改名失败：" + e.getMessage());
        }
    }

    // ==================== 文化-标签关联（补充接口，供后台文化编辑表单使用） ====================

    /** 读取某文化已绑定的标签（编辑表单回显） */
    @GetMapping("/culture")
    public ApiResult<List<Tag>> cultureTags(@RequestParam(value = "cultureId", required = false) Long cultureId,
                                            HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        return ApiResult.ok(tagService.tagsOfCulture(cultureId));
    }

    /**
     * 重设某文化的标签（先删后插，事务）。
     * body: { cultureId: 1, tagIds: [1,2,3] }（tagIds 为空表示清空标签）
     */
    @PostMapping("/bind")
    public ApiResult<Void> bind(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        try {
            Long cultureId = toLong(body.get("cultureId"));
            request.setAttribute("oplogTargetId", cultureId);
            tagService.setCultureTags(cultureId, toLongList(body.get("tagIds")));
            return ApiResult.ok(null);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("保存失败：" + e.getMessage());
        }
    }

    /** body 里的 id 数组容错解析（支持数字/字符串、null） */
    @SuppressWarnings("unchecked")
    private List<Long> toLongList(Object v) {
        if (!(v instanceof List)) return null;
        List<Long> ids = new java.util.ArrayList<>();
        for (Object o : (List<Object>) v) {
            Long id = toLong(o);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private Long loginUserId(HttpServletRequest request) {
        Object v = request.getAttribute(JwtAuthFilter.ATTR_LOGIN_USER_ID);
        return v == null ? null : (Long) v;
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        try {
            return Long.valueOf(String.valueOf(v).trim());
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
}
