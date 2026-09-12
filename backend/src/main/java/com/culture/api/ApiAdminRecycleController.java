package com.culture.api;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Role;
import com.culture.service.RecycleService;
import com.culture.service.RoleService;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 后台回收站 API（/api/admin/recycle，需后台令牌 + 管理员角色）。
 *
 * <pre>
 * GET  /api/admin/recycle/counts                                  各类型已删除条数（角标）
 * GET  /api/admin/recycle/list?type=&keyword=&page=1&pageSize=10  已删除内容分页
 * POST /api/admin/recycle/purge   body {type, ids:[...]}          彻底删除（物理删除）
 * </pre>
 *
 * <p>与既有「删除可撤销」的关系：日常删除走各实体的 /delete（逻辑删除 deleted=1），
 * 各实体的 /restore 可以把它们恢复回来；本控制器是回收站视图 + 不可恢复的彻底删除。</p>
 *
 * <p>权限：路径在 /api/admin/** 下（JwtAuthFilter + Spring Security 已覆盖未登录），
 * 这里再按既有写法做一次管理员角色校验，非管理员返回 {@code code:403}。</p>
 *
 * <p>操作日志：POST 由 OperationLogInterceptor 按路径自动记录（module=recycle，action=purge），
 * JSON body 拦截器读不到，所以控制器主动写 request 属性 oplogTargetId。</p>
 */
@RestController
@RequestMapping("/api/admin/recycle")
public class ApiAdminRecycleController {

    @Autowired
    private RecycleService recycleService;

    @Autowired
    private RoleService roleService;

    /** 校验当前用户是否管理员（与 ApiAdminController / ApiAdminTagController 写法保持一致） */
    private boolean isAdmin(Long userId) {
        if (userId == null) return false;
        for (Role r : roleService.listRoleByUserId(userId)) {
            if ("管理员".equals(r.getName())) return true;
        }
        return false;
    }

    /** 当前登录用户 id（JwtAuthFilter 写入的请求属性） */
    private Long currentUserId(HttpServletRequest request) {
        Object v = request.getAttribute(JwtAuthFilter.ATTR_LOGIN_USER_ID);
        return v == null ? null : (Long) v;
    }

    /**
     * 回收站角标：data = {culture, category, tag, announcement, sentence, comment, user}，
     * 每条都是 deleted=1 的条数，一条 SQL 一次查完。
     */
    @GetMapping("/counts")
    public ApiResult<Map<String, Object>> counts(HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            return ApiResult.ok(recycleService.counts());
        } catch (Exception e) {
            return ApiResult.error("查询失败：" + e.getMessage());
        }
    }

    /**
     * 回收站分页列表。
     *
     * @param type     必填：culture|category|tag|announcement|sentence|comment|user（非法 → 400 业务错误）
     * @param keyword  可选：按各实体的名称/内容字段模糊匹配
     * @param page     可选：默认 1，&lt;1 按 1
     * @param pageSize 可选：默认 10，&lt;1 按 10，&gt;100 按 100
     * @return data = { total, rows:[{id,title,summary,extra,createTime,deletedTime}] }
     */
    @GetMapping("/list")
    public ApiResult<PageList> list(@RequestParam(value = "type", required = false) String type,
                                    @RequestParam(value = "keyword", required = false) String keyword,
                                    @RequestParam(value = "page", required = false) Integer page,
                                    @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                    HttpServletRequest request) {
        if (!isAdmin(currentUserId(request))) return ApiResult.error(403, "无权限");
        try {
            return ApiResult.ok(recycleService.list(type, keyword, page, pageSize));
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("查询失败：" + e.getMessage());
        }
    }

    /**
     * 彻底删除（物理删除，不可恢复）。
     * body: <code>{ "type": "culture", "ids": [1,2,3] }</code>
     * → data <code>{ count: 主表实际删除行数, relationCount: 级联清理的关联行数 }</code>。
     *
     * <p>只允许删「已逻辑删除（deleted=1）」的行；ids 为空/全非法或超过 200 条 → 400；
     * type 非法 → 400；用户类型另有「不能删自己 / 不能删最后一个管理员」的保护（400）。</p>
     */
    @PostMapping("/purge")
    public ApiResult<Map<String, Object>> purge(@RequestBody(required = false) Map<String, Object> body,
                                                HttpServletRequest request) {
        Long uid = currentUserId(request);
        if (!isAdmin(uid)) return ApiResult.error(403, "无权限");
        try {
            if (body == null) {
                throw new BusinessException("参数错误：请传入 type（类型）与 ids（要彻底删除的 id 列表）");
            }
            String type = body.get("type") == null ? null : String.valueOf(body.get("type"));
            List<Long> ids = toLongList(body.get("ids"));
            // JSON body 拦截器读不到，主动告知操作日志目标
            request.setAttribute("oplogTargetId", oplogTarget(type, ids));
            return ApiResult.ok(recycleService.purge(type, ids, uid));
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("彻底删除失败：" + e.getMessage());
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

    /** 操作日志目标：type#n=数量:前若干个 id（拦截器还会再截断到 64 字符） */
    private String oplogTarget(String type, List<Long> ids) {
        StringBuilder sb = new StringBuilder(type == null ? "?" : type);
        if (ids == null || ids.isEmpty()) {
            return sb.toString();
        }
        sb.append("#n=").append(ids.size()).append(':');
        for (int i = 0; i < ids.size() && i < 5; i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.toString();
    }
}
