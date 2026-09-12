package com.culture.api;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Role;
import com.culture.service.RoleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 后台「角色管理」API（/api/admin/role，需后台令牌 + 管理员角色）。
 *
 * <pre>
 * GET  /api/admin/role/list     所有未删除角色 + userCount
 * POST /api/admin/role/save     body {id?, code, name, description, sort, status} 新增/编辑
 * POST /api/admin/role/delete   body {id} 逻辑删除（顺带清理 sys_role_permission）
 * </pre>
 *
 * <p>按钮级授权不在这里，见 {@link ApiAdminPermissionController} 的
 * {@code GET /api/admin/permission/buttons} 与 {@code POST /api/admin/permission/button}。</p>
 *
 * <p>鉴权：路径本身在 /api/admin/** 下（WebSecurityConfig 已要求已认证，JwtAuthFilter 负责
 * 前后台双令牌），本控制器再按现有后台控制器的做法做一次「管理员角色」校验，非管理员返回
 * code=403 业务错误。写操作由 OperationLogInterceptor 自动记录（module=role）。</p>
 */
@RestController
@RequestMapping("/api/admin/role")
public class ApiAdminRoleController {

    @Autowired
    private RoleService roleService;

    /** 校验当前用户是否管理员（与 ApiAdminPermissionController / ApiAdminTagController 保持一致） */
    private boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        for (Role r : roleService.listRoleByUserId(userId)) {
            if ("管理员".equals(r.getName())) {
                return true;
            }
        }
        return false;
    }

    private Long loginUserId(HttpServletRequest request) {
        Object v = request.getAttribute(JwtAuthFilter.ATTR_LOGIN_USER_ID);
        return v == null ? null : (Long) v;
    }

    /**
     * 角色列表（只含 deleted = 0，按 sort,id 排序）。
     * data: [{ id, code, name, description, sort, status, userCount }, ...]
     * userCount 由一条 GROUP BY 查询算出，不做 N+1。
     */
    @GetMapping("/list")
    public ApiResult<List<Map<String, Object>>> list(HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) {
            return ApiResult.error(403, "无权限");
        }
        try {
            return ApiResult.ok(roleService.listRoles());
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("查询失败：" + e.getMessage());
        }
    }

    /**
     * 新增（无 id / id 为 null）或编辑（有 id）角色。
     *
     * <p>请求体：{@code {"id":2,"code":"editor","name":"编辑","description":"内容编辑","sort":10,"status":1}}；
     * 新增时不传 id。响应：{@code code=200, data={id, code, name, description, sort, status}}。</p>
     *
     * <p>校验与保护规则（不通过返回 code=400 + 明确文案）：
     * code 必填且匹配 {@code [a-zA-Z0-9_-]{2,32}}、唯一；name 必填且唯一；
     * id=1「超级管理员」的 code 不允许改、不允许停用；
     * 不允许把「最后一个启用中的管理员角色」停用。</p>
     */
    @PostMapping("/save")
    public ApiResult<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> body,
                                               HttpServletRequest request) {
        Long operatorId = loginUserId(request);
        if (!isAdmin(operatorId)) {
            return ApiResult.error(403, "无权限");
        }
        try {
            if (body == null) {
                throw new BusinessException("请求体不能为空");
            }
            Long id = toLong(body.get("id"));
            if (body.get("id") != null && id == null) {
                throw new BusinessException("id 格式不正确");
            }
            Integer sort = toInteger(body.get("sort"));
            if (body.get("sort") != null && sort == null) {
                throw new BusinessException("sort 必须是整数");
            }
            Integer status = toInteger(body.get("status"));
            if (body.get("status") != null && status == null) {
                throw new BusinessException("status 必须是 0（停用）或 1（启用）");
            }
            if (status != null && status.intValue() != 0 && status.intValue() != 1) {
                throw new BusinessException("status 只能是 0（停用）或 1（启用）");
            }

            // 供操作日志拦截器提取目标 id（拦截器读不到 JSON body，这里主动告知）
            request.setAttribute("oplogTargetId", id);

            Map<String, Object> data = roleService.saveRole(id,
                    toStr(body.get("code")),
                    toStr(body.get("name")),
                    toStr(body.get("description")),
                    sort,
                    status);

            // 新增时 id 由 useGeneratedKeys 回填，这里再补一次目标 id（编辑时是同一个值）
            request.setAttribute("oplogTargetId", data.get("id"));
            return ApiResult.ok(data);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("保存失败：" + e.getMessage());
        }
    }

    /**
     * 逻辑删除角色（deleted=1）并清理该角色在 sys_role_permission 里的全部授权。
     *
     * <p>请求体：{@code {"id": 3}}；响应：{@code data={count:1, permissionCount:N}}，
     * count = 逻辑删除的角色数（0/1），permissionCount = 顺带清理掉的授权行数。</p>
     *
     * <p>保护：id=1 不可删；角色下仍有 sys_user_role 引用时返回 400
     * 「该角色下还有 N 个用户，请先调整这些用户的角色后再删除」。</p>
     */
    @PostMapping("/delete")
    public ApiResult<Map<String, Object>> delete(@RequestBody(required = false) Map<String, Object> body,
                                                 HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) {
            return ApiResult.error(403, "无权限");
        }
        try {
            if (body == null) {
                throw new BusinessException("请求体不能为空");
            }
            Long id = toLong(body.get("id"));
            if (id == null) {
                throw new BusinessException("id 不能为空或格式不正确");
            }
            request.setAttribute("oplogTargetId", id);
            return ApiResult.ok(roleService.deleteRole(id));
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("删除失败：" + e.getMessage());
        }
    }

    // ==================== 参数解析（与 ApiAdminTagController 风格一致） ====================

    private String toStr(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer toInteger(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return Integer.valueOf(((Number) v).intValue());
        }
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
