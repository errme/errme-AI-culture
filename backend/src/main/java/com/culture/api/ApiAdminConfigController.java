package com.culture.api;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Role;
import com.culture.entity.SysConfig;
import com.culture.service.ConfigService;
import com.culture.service.RoleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台「系统设置」接口（表 {@code sys_config}）。
 *
 * <pre>
 * GET  /api/admin/config/list    全部配置（含分组、类型、范围、说明、最后修改人）
 * POST /api/admin/config/save    body { values: { key: value, ... } }  批量保存（先整体校验再落库）
 * POST /api/admin/config/reset   body { key }                          恢复某项为出厂默认
 * POST /api/admin/config/reload                                       从数据库重新载入缓存
 * </pre>
 *
 * <p>权限：与其它后台接口同一口径（{@code /api/admin/**} 需后台令牌 + 管理员角色）。</p>
 *
 * <p><b>为什么不需要重启</b>：{@code ConfigService} 在写入后立即刷新内存缓存，
 * 而所有读取方都走 {@code ConfigService}，因此改完即时生效。</p>
 */
@RestController
@RequestMapping("/api/admin/config")
public class ApiAdminConfigController {

    private final ConfigService configService;
    private final RoleService roleService;

    public ApiAdminConfigController(ConfigService configService, RoleService roleService) {
        this.configService = configService;
        this.roleService = roleService;
    }

    /**
     * 全部配置项。
     *
     * <p>同时按 {@code config_group} 归组返回，前端可直接渲染分区表单，
     * 不需要在前端维护「哪个 key 属于哪一组」的映射（那也是写死）。</p>
     */
    @GetMapping("/list")
    public ApiResult<Map<String, Object>> list(HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");

        List<SysConfig> all = configService.list();

        // 分组聚合：保持后端给的顺序（config_group, sort, id）
        Map<String, List<SysConfig>> groups = new LinkedHashMap<>();
        for (SysConfig c : all) {
            groups.computeIfAbsent(c.getConfigGroup(), k -> new ArrayList<>()).add(c);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", all.size());
        data.put("items", all);
        data.put("groups", groups);
        // 分组的中文名也由后端给出，前端不再写死一份对照表
        data.put("groupLabels", groupLabels());
        return ApiResult.ok(data);
    }

    /** 批量保存：{ "values": { "site.name": "xxx", ... } } */
    @PostMapping("/save")
    @SuppressWarnings("unchecked")
    public ApiResult<Map<String, Object>> save(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long operatorId = loginUserId(request);
        if (!isAdmin(operatorId)) return ApiResult.error(403, "无权限");

        Object raw = body == null ? null : body.get("values");
        if (!(raw instanceof Map)) {
            return ApiResult.error("参数错误：缺少 values");
        }
        Map<String, Object> src = (Map<String, Object>) raw;
        if (src.isEmpty()) {
            return ApiResult.error("没有需要保存的配置项");
        }

        Map<String, String> entries = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : src.entrySet()) {
            entries.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }

        try {
            int affected = configService.update(entries, operatorId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", affected);
            return ApiResult.ok("已保存 " + affected + " 项配置", data);
        } catch (BusinessException e) {
            // 校验类错误原样返回给前端（文案本来就是给用户看的）
            return ApiResult.error(e.getMessage());
        }
    }

    /** 恢复某项为出厂默认：{ "key": "site.name" } */
    @PostMapping("/reset")
    public ApiResult<Map<String, Object>> reset(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long operatorId = loginUserId(request);
        if (!isAdmin(operatorId)) return ApiResult.error(403, "无权限");

        String key = body == null || body.get("key") == null ? null : String.valueOf(body.get("key"));
        try {
            int affected = configService.resetToDefault(key, operatorId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", affected);
            return ApiResult.ok("已恢复默认值", data);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        }
    }

    /**
     * 从数据库重新载入配置缓存。
     *
     * <p>用于「直接在数据库改了值」的场景（例如运维用 SQL 批量改），
     * 不必重启服务就能让内存缓存同步。</p>
     */
    @PostMapping("/reload")
    public ApiResult<Void> reload(HttpServletRequest request) {
        if (!isAdmin(loginUserId(request))) return ApiResult.error(403, "无权限");
        configService.refresh();
        return ApiResult.ok("已重新载入配置", null);
    }

    /** 分组中文名（与 docs/sql/13_sys_config.sql 里的 config_group 取值对应） */
    private Map<String, String> groupLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("site", "站点信息");
        m.put("code", "验证码策略");
        m.put("jwt", "登录有效期");
        m.put("cache", "缓存");
        m.put("upload", "上传限制");
        m.put("limit", "接口限流");
        m.put("common", "通用");
        return m;
    }

    private Long loginUserId(HttpServletRequest request) {
        Object v = request.getAttribute(JwtAuthFilter.ATTR_LOGIN_USER_ID);
        return v == null ? null : (Long) v;
    }

    private boolean isAdmin(Long userId) {
        if (userId == null) return false;
        List<Role> roles = roleService.listRoleByUserId(userId);
        if (roles == null) return false;
        for (Role r : roles) {
            if (JwtAuthFilter.ROLE_ADMIN_NAME.equals(r.getName())) return true;
        }
        return false;
    }
}
