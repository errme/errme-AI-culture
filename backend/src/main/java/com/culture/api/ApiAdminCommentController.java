package com.culture.api;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Role;
import com.culture.query.CommentQuery;
import com.culture.service.CommentService;
import com.culture.service.RoleService;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台评论管理 API（/api/admin/comment，需后台令牌 + 管理员角色）。
 *
 * <pre>
 * GET  /api/admin/comment/list         分页 + status（-1 全部）+ keyword（内容/昵称）
 * GET  /api/admin/comment/pending      { pending: n }
 * POST /api/admin/comment/audit        body { id, status }（1 通过 2 拒绝）
 * POST /api/admin/comment/delete       body { id }
 * POST /api/admin/comment/batch-audit  body { ids: [1,2,3], status: 1 } -> { count }
 * POST /api/admin/comment/batch-delete body { ids: [1,2,3] }             -> { count }
 * POST /api/admin/comment/restore      body {id} 或 {ids:[1,2,3]}        -> { count }（删除可撤销）
 * POST /api/admin/comment/recheck      body { ids: [1,2,3] }             -> { checked, rejected }（重新过敏感词表）
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/comment")
public class ApiAdminCommentController {

    @Autowired
    private CommentService commentService;
    @Autowired
    private RoleService roleService;

    private boolean isAdmin(Long userId) {
        if (userId == null) return false;
        for (Role r : roleService.listRoleByUserId(userId)) {
            if ("管理员".equals(r.getName())) return true;
        }
        return false;
    }

    private Long loginUserId(HttpServletRequest request) {
        Object v = request.getAttribute(JwtAuthFilter.ATTR_LOGIN_USER_ID);
        return v == null ? null : (Long) v;
    }

    /** 评论分页列表 */
    @GetMapping("/list")
    public ApiResult<PageList> list(CommentQuery query, HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        return ApiResult.ok(commentService.adminPage(query));
    }

    /** 待审数量（后台菜单角标） */
    @GetMapping("/pending")
    public ApiResult<Map<String, Object>> pending(HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pending", commentService.pendingCount());
        return ApiResult.ok(data);
    }

    /** 审核：通过/拒绝，记录审核人与审核时间 */
    @PostMapping("/audit")
    public ApiResult<Void> audit(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long uid = loginUserId(request);
        if (!isAdmin(uid)) return ApiResult.error(403, "无权限");
        try {
            Long id = toLong(body.get("id"));
            int status = body.get("status") == null ? 1 : Integer.parseInt(String.valueOf(body.get("status")).trim());
            request.setAttribute("oplogTargetId", id);
            commentService.audit(id, status, uid);
            return ApiResult.ok(null);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("审核失败：" + e.getMessage());
        }
    }

    /** 删除（逻辑删除） */
    @PostMapping("/delete")
    public ApiResult<Void> delete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        try {
            Long id = toLong(body.get("id"));
            request.setAttribute("oplogTargetId", id);
            commentService.delete(id);
            return ApiResult.ok(null);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("删除失败：" + e.getMessage());
        }
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 批量操作（增量追加） ====================

    /**
     * 批量审核：body { ids: [1,2,3], status: 1 }（1 通过 / 2 拒绝，与单条 audit 同语义）。
     * 返回 data { count: 实际处理条数 }；ids 为空或超过 500 条返回 400 业务错误（不是 500）。
     */
    @PostMapping("/batch-audit")
    public ApiResult<Map<String, Object>> batchAudit(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long uid = loginUserId(request);
        if (!isAdmin(uid)) return ApiResult.error(403, "无权限");
        int status = 1;
        if (body.get("status") != null) {
            try {
                status = Integer.parseInt(String.valueOf(body.get("status")).trim());
            } catch (NumberFormatException e) {
                return ApiResult.error("审核状态只能是 1（通过）或 2（拒绝）");
            }
        }
        try {
            List<Long> ids = toLongList(body.get("ids"));
            // JSON body 拦截器读不到，主动告知操作日志目标
            request.setAttribute("oplogTargetId", oplogTarget(ids));
            int count = commentService.batchAudit(ids, status, uid);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", count);
            return ApiResult.ok(data);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("批量审核失败：" + e.getMessage());
        }
    }

    /**
     * 批量删除（逻辑删除）：body { ids: [1,2,3] }。返回 data { count: 实际处理条数 }。
     */
    @PostMapping("/batch-delete")
    public ApiResult<Map<String, Object>> batchDelete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        try {
            List<Long> ids = toLongList(body.get("ids"));
            request.setAttribute("oplogTargetId", oplogTarget(ids));
            int count = commentService.batchDelete(ids);
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
     * 重新过敏感词表：body { ids: [1,2,3] }，返回 data { checked, rejected }。
     *
     * <p>只处理这些 id 里「待审核（status=0）且未删除」的评论：命中 action=1（直接拒绝）的词
     * 直接置为已拒绝（status=2）；命中 action=2 的不动（本来就是待审）。
     * 用于「词表新增了强规则后，把历史待审评论再筛一遍」。参数为空或超过 500 条返回 400。</p>
     */
    @PostMapping("/recheck")
    public ApiResult<Map<String, Object>> recheck(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long uid = loginUserId(request);
        if (!isAdmin(uid)) return ApiResult.error(403, "无权限");
        try {
            List<Long> ids = toLongList(body.get("ids"));
            request.setAttribute("oplogTargetId", oplogTarget(ids));
            return ApiResult.ok(commentService.recheckSensitive(ids, uid));
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("重新过词表失败：" + e.getMessage());
        }
    }

    /**
     * 恢复被逻辑删除的评论：body <code>{id}</code> 或 <code>{ids:[1,2,3]}</code>，
     * 返回 data { count: 实际恢复条数 }。参数为空或超过 500 条返回 400 业务错误。
     *
     * <p>恢复只把 deleted 改回 0，<b>status / audited_by / audited_at 保持原样</b>：
     * 删除前是「已通过(1)」的评论恢复后仍是已通过，不需要重新审核。
     * 权限与 /comment/delete 一致：必须是管理员。</p>
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
                count = commentService.restoreBatch(ids);
            } else if (id != null) {
                request.setAttribute("oplogTargetId", String.valueOf(id));
                count = commentService.restore(id);
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
