package com.culture.api;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Role;
import com.culture.entity.SensitiveWord;
import com.culture.service.RoleService;
import com.culture.service.SensitiveWordService;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台敏感词管理 API（/api/admin/sensitive，需后台令牌 + 管理员角色）。
 *
 * <p>为什么单独建控制器而不是并入 ApiAdminCommentController：路由前缀不同
 * （/api/admin/sensitive vs /api/admin/comment），且词表是独立资源；
 * 与 /api/admin/tag、/api/admin/log 的「一个资源一个控制器」风格保持一致。
 * 评论侧的「重新过词表」端点 /api/admin/comment/recheck 仍放在 ApiAdminCommentController。</p>
 *
 * <pre>
 * GET  /api/admin/sensitive/list?keyword=&page=1&pageSize=20   分页（含停用词，不含已删除）-> data { total, rows }
 * POST /api/admin/sensitive/save         body { id?, word, action, enabled, remark } -> data { id, word, created }
 * POST /api/admin/sensitive/delete       body { id }                                  -> data null（逻辑删除）
 * POST /api/admin/sensitive/batch-delete body { ids: [1,2,3] }                        -> data { count }
 * </pre>
 *
 * <p>权限校验方式与 ApiAdminCommentController / ApiAdminTagController 完全一致：
 * 取 JwtAuthFilter 写入的 loginUserId，再查角色名是否「管理员」。</p>
 */
@RestController
@RequestMapping("/api/admin/sensitive")
public class ApiAdminSensitiveController {

    @Autowired
    private SensitiveWordService sensitiveWordService;
    @Autowired
    private RoleService roleService;

    /** 校验当前用户是否管理员（与其他后台控制器保持一致） */
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

    /** 分页列表：data { total, rows }（rows 里含停用词，方便后台一键启停） */
    @GetMapping("/list")
    public ApiResult<PageList> list(@RequestParam(value = "keyword", required = false) String keyword,
                                    @RequestParam(value = "page", required = false) Integer page,
                                    @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                    HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        return ApiResult.ok(sensitiveWordService.page(keyword, page, pageSize));
    }

    /**
     * 新增（无 id）或编辑（有 id）：
     * body { id?, word, action, enabled, remark }，action 1=直接拒绝 2=转待审核，enabled 1=启用 0=停用。
     * 词必填、≤100 字、不可重复（重复返回 400）。
     */
    @PostMapping("/save")
    public ApiResult<Map<String, Object>> save(@RequestBody SensitiveWord body, HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        try {
            Map<String, Object> data = sensitiveWordService.save(body);
            // 供操作日志拦截器提取目标 id（拦截器读不到 JSON body，这里主动告知）
            request.setAttribute("oplogTargetId", data.get("id"));
            return ApiResult.ok(data);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("保存失败：" + e.getMessage());
        }
    }

    /** 逻辑删除单条：body { id } */
    @PostMapping("/delete")
    public ApiResult<Void> delete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        try {
            Long id = toLong(body.get("id"));
            request.setAttribute("oplogTargetId", id);
            sensitiveWordService.delete(id);
            return ApiResult.ok(null);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("删除失败：" + e.getMessage());
        }
    }

    /**
     * 批量逻辑删除：body { ids: [1,2,3] }，返回 data { count: 实际删除条数 }。
     * ids 为空或超过 500 条返回 400 业务错误（不是 500）。
     */
    @PostMapping("/batch-delete")
    public ApiResult<Map<String, Object>> batchDelete(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        try {
            List<Long> ids = toLongList(body.get("ids"));
            request.setAttribute("oplogTargetId", oplogTarget(ids));
            int count = sensitiveWordService.batchDelete(ids);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", count);
            return ApiResult.ok(data);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("批量删除失败：" + e.getMessage());
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
