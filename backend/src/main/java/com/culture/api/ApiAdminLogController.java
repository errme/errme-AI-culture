package com.culture.api;

import com.culture.entity.Role;
import com.culture.query.OperationLogQuery;
import com.culture.service.OperationLogService;
import com.culture.service.RoleService;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * 后台操作日志 API（/api/admin/log，需后台令牌 + 管理员角色）。
 *
 * <pre>
 * GET /api/admin/log/list?page=&pageSize=&module=&keyword=   分页查询（module + 用户名/摘要模糊）
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/log")
public class ApiAdminLogController {

    @Autowired
    private OperationLogService operationLogService;
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

    /** 操作日志分页列表 */
    @GetMapping("/list")
    public ApiResult<PageList> list(OperationLogQuery query, HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        return ApiResult.ok(operationLogService.page(query));
    }
}
