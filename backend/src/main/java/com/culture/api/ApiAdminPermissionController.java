package com.culture.api;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Role;
import com.culture.service.PermissionService;
import com.culture.service.RoleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台「菜单/目录角色权限配置」API（/api/admin/permission，需后台令牌 + 管理员角色）。
 *
 * <pre>
 * GET  /api/admin/permission/menus   菜单/目录扁平列表（勾选树用）
 * GET  /api/admin/permission/roles   所有启用角色 + 已授予的 menuIds
 * POST /api/admin/permission/role    body { roleId, menuIds:[...] } 全量覆盖角色的菜单权限
 * </pre>
 *
 * <p>鉴权：路径本身在 /api/admin/** 下（WebSecurityConfig 已要求已认证，JwtAuthFilter 负责
 * 前后台双令牌），本控制器再按现有后台控制器的做法做一次「管理员角色」校验，非管理员返回
 * code=403 业务错误。</p>
 */
@RestController
@RequestMapping("/api/admin/permission")
public class ApiAdminPermissionController {

    @Autowired
    private PermissionService permissionService;
    @Autowired
    private RoleService roleService;

    /** 校验当前用户是否管理员（与 ApiAdminController / ApiAdminLogController 保持一致） */
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
     * 菜单/目录扁平列表（只含 deleted=0，按 pid,sort,id 排序），供前端勾选树使用。
     * data: [{ id, name, url, icon, pid, sort, status }, ...]
     */
    @GetMapping("/menus")
    public ApiResult<List<Map<String, Object>>> menus(HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) {
            return ApiResult.error(403, "无权限");
        }
        return ApiResult.ok(permissionService.listMenus());
    }

    /**
     * 所有启用角色及其已授予的菜单 id。
     * data: [{ id, code, name, description, menuIds: [1,2,3] }, ...]
     */
    @GetMapping("/roles")
    public ApiResult<List<Map<String, Object>>> roles(HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) {
            return ApiResult.error(403, "无权限");
        }
        return ApiResult.ok(permissionService.listRolesWithMenuIds());
    }

    /**
     * 全量覆盖某角色的菜单权限。
     *
     * <p>请求体：{@code {"roleId": 2, "menuIds": [1, 2, 5, 102]}}。
     * menuIds 允许空数组（= 撤销全部菜单权限，按钮类权限保留）；但必须显式传该字段，
     * 缺字段直接 400，避免误清空。</p>
     *
     * <p>响应：{@code code=200, data={count: 4, warning: null}}；若本次保存会让当前登录人
     * 失去「菜单权限」菜单的可见性，data.warning 为提示文案（仍然保存成功）。</p>
     */
    @PostMapping("/role")
    public ApiResult<Map<String, Object>> saveRoleMenus(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request) {
        Long operatorId = loginUserId(request);
        if (!isAdmin(operatorId)) {
            return ApiResult.error(403, "无权限");
        }
        try {
            if (body == null || !body.containsKey("menuIds")) {
                return ApiResult.error("缺少 menuIds 字段（本接口为全量覆盖，如需清空请传空数组 []）");
            }
            Long roleId = toLong(body.get("roleId"));
            if (roleId == null) {
                return ApiResult.error("roleId 不能为空或格式不正确");
            }
            List<Long> menuIds = toLongList(body.get("menuIds"));

            // 供操作日志拦截器提取目标 id（拦截器读不到 JSON body，这里主动告知）
            request.setAttribute("oplogTargetId", roleId);

            // 防自锁提示要在保存前算（保存后该角色数据已变，但判断本身只用提交的 menuIds）
            String warning = selfLockWarning(operatorId, roleId, menuIds);

            int count = permissionService.grantMenus(roleId, menuIds);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", count);
            data.put("warning", warning);
            return ApiResult.ok(data);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("保存失败：" + e.getMessage());
        }
    }

    // ==================== 按钮级权限（增量新增） ====================

    /**
     * 按钮级权限配置数据：全部可配置按钮 + 某角色已授予的按钮 id。
     *
     * <p>只包含 {@code sys_permission.menu_id IS NULL AND deleted = 0} 的行
     * （菜单类权限由 {@code /menus} + {@code /roles} + {@code /role} 负责）。</p>
     *
     * <p>请求：{@code GET /api/admin/permission/buttons?roleId=2}；
     * 响应：{@code data={all:[{id,name,title,sort}], granted:[2,3,4]}}。
     * roleId 缺失或角色不存在 → 400。</p>
     */
    @GetMapping("/buttons")
    public ApiResult<Map<String, Object>> buttons(HttpServletRequest request,
                                                  @RequestParam(value = "roleId", required = false) Long roleId) {
        if (!isAdmin(loginUserId(request))) {
            return ApiResult.error(403, "无权限");
        }
        try {
            return ApiResult.ok(permissionService.listButtons(roleId));
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("查询失败：" + e.getMessage());
        }
    }

    /**
     * 全量覆盖某角色的「按钮类」授权。
     *
     * <p>请求体：{@code {"roleId": 2, "permissionIds": [2, 3]}}；
     * permissionIds 允许空数组（= 撤销全部按钮权限，菜单类权限保留），但必须显式传该字段，
     * 缺字段直接 400，避免误清空。</p>
     *
     * <p>只允许提交 {@code menu_id IS NULL} 的权限 id：传了菜单类权限 id、已逻辑删除的 id
     * 或根本不存在的 id 都返回 400 并说明是哪一个，避免与「菜单权限」互相覆盖。</p>
     *
     * <p>响应：{@code code=200, data={count: 2}}（count = 实际授予的按钮数，入参已去重）。</p>
     */
    @PostMapping("/button")
    public ApiResult<Map<String, Object>> saveRoleButtons(@RequestBody(required = false) Map<String, Object> body,
                                                          HttpServletRequest request) {
        Long operatorId = loginUserId(request);
        if (!isAdmin(operatorId)) {
            return ApiResult.error(403, "无权限");
        }
        try {
            if (body == null || !body.containsKey("permissionIds")) {
                return ApiResult.error("缺少 permissionIds 字段（本接口为全量覆盖，如需清空请传空数组 []）");
            }
            Long roleId = toLong(body.get("roleId"));
            if (roleId == null) {
                return ApiResult.error("roleId 不能为空或格式不正确");
            }
            List<Long> permissionIds = toLongListOf(body.get("permissionIds"), "permissionIds");

            // 供操作日志拦截器提取目标 id（拦截器读不到 JSON body，这里主动告知）
            request.setAttribute("oplogTargetId", roleId);

            int count = permissionService.grantButtons(roleId, permissionIds);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", count);
            return ApiResult.ok(data);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("保存失败：" + e.getMessage());
        }
    }

    // ==================== 防自锁 ====================

    /**
     * 防自锁判断：被改角色是否是当前登录人所属角色之一，且新 menuIds 不含「菜单权限」菜单，
     * 且当前登录人没有其它角色仍能看见该菜单 —— 满足则返回警告文案（不改语义，仍然允许保存）。
     *
     * <p>返回 null 表示无需警告，包括：菜单权限菜单还没入库（05_menu_permission.sql 未执行）、
     * 改的不是自己的角色、新集合里保留了该菜单、或自己还有别的角色持有该菜单。</p>
     */
    private String selfLockWarning(Long operatorId, Long roleId, List<Long> menuIds) {
        Long permissionMenuId = permissionService.findMenuIdByUrl(PermissionService.PERMISSION_MENU_URL);
        if (permissionMenuId == null) {
            return null;
        }
        if (menuIds != null && menuIds.contains(permissionMenuId)) {
            return null;
        }
        List<Role> myRoles = roleService.listRoleByUserId(operatorId);
        if (myRoles == null || myRoles.isEmpty()) {
            return null;
        }
        boolean editingMyRole = false;
        for (Role r : myRoles) {
            if (roleId.equals(r.getId())) {
                editingMyRole = true;
                break;
            }
        }
        if (!editingMyRole) {
            return null;
        }
        // 自己还有其它角色能看见该菜单 → 不会自锁
        for (Role r : myRoles) {
            if (roleId.equals(r.getId())) {
                continue;
            }
            List<Long> otherMenuIds = permissionService.listMenuIdsByRoleId(r.getId());
            if (otherMenuIds != null && otherMenuIds.contains(permissionMenuId)) {
                return null;
            }
        }
        return "保存后当前账号将失去「菜单权限」菜单的可见性，且没有其它角色可以恢复，请确认是否继续";
    }

    // ==================== 参数解析（与 ApiAdminTagController 风格一致） ====================

    /**
     * 带字段名的 id 数组容错解析（支持数字/字符串；null 视为空数组）。
     * 与既有 {@link #toLongList(Object)} 逻辑一致，只是错误文案里带上字段名，
     * 供 /button 的 permissionIds 复用（不改动既有方法，避免影响 /role 的行为）。
     */
    @SuppressWarnings("unchecked")
    private List<Long> toLongListOf(Object v, String field) {
        List<Long> ids = new ArrayList<>();
        if (v == null) {
            return ids;
        }
        if (!(v instanceof List)) {
            throw new BusinessException(field + " 必须是数组");
        }
        for (Object o : (List<Object>) v) {
            if (o == null) {
                continue;
            }
            Long id = toLong(o);
            if (id == null) {
                throw new BusinessException(field + " 含非法 id：" + o);
            }
            ids.add(id);
        }
        return ids;
    }

    /** body 里的 id 数组容错解析（支持数字/字符串）；非数组、含非法 id 一律抛业务错误（→400） */
    @SuppressWarnings("unchecked")
    private List<Long> toLongList(Object v) {
        List<Long> ids = new ArrayList<>();
        if (v == null) {
            return ids;
        }
        if (!(v instanceof List)) {
            throw new BusinessException("menuIds 必须是数组");
        }
        for (Object o : (List<Object>) v) {
            if (o == null) {
                continue;
            }
            Long id = toLong(o);
            if (id == null) {
                throw new BusinessException("menuIds 含非法 id：" + o);
            }
            ids.add(id);
        }
        return ids;
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
}
