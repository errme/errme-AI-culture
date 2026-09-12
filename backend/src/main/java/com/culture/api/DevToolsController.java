package com.culture.api;

import com.culture.entity.Role;
import com.culture.service.RoleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台「运维工具」入口清单。
 *
 * <p><b>为什么要有这个接口：</b>Druid 监控页与 API 文档的访问路径都是<b>可配置</b>的
 * （见 {@code app.druid.stat.path} / {@code app.api-doc.ui-path}）。
 * 如果在前端把 {@code /druid/}、{@code /swagger-ui.html} 写死成菜单链接，
 * 那么运维一旦改了配置路径，菜单就会指向一个 404 —— 这正是「不要写死」要避免的情况。</p>
 *
 * <p>因此改由后端根据<b>当前生效的配置</b>输出清单，前端只负责渲染：
 * 改了配置 → 菜单自动跟着变，两端不会不一致。</p>
 *
 * <p>返回项形如：</p>
 * <pre>
 * {
 *   "key": "druid",
 *   "name": "数据库监控",
 *   "url": "/druid/",
 *   "icon": "mdi mdi-database-eye",
 *   "description": "连接池 / SQL / URI 监控（有独立登录）",
 *   "newTab": true
 * }
 * </pre>
 *
 * <p>权限：本接口在 {@code /api/admin/**} 下，只有后台令牌或管理员会话可达；
 * 每一项自身是否可用由它的 enabled 配置决定（关闭时不出现在清单里）。</p>
 */
@RestController
@RequestMapping("/api/admin/devtools")
public class DevToolsController {

    private final RoleService roleService;

    public DevToolsController(RoleService roleService) {
        this.roleService = roleService;
    }

    @Value("${app.druid.stat.enabled:true}")
    private boolean druidEnabled;
    @Value("${app.druid.stat.path:/druid/*}")
    private String druidPath;

    @Value("${app.api-doc.enabled:true}")
    private boolean apiDocEnabled;
    @Value("${app.api-doc.ui-path:/swagger-ui.html}")
    private String apiDocUiPath;

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.ATTR_LOGIN_USER_ID);
        if (!isAdmin(userId)) {
            return ApiResult.error(403, "无权限");
        }

        List<Map<String, Object>> tools = new ArrayList<>();

        if (druidEnabled) {
            tools.add(tool("druid", "数据库监控", normalizeDruidUrl(druidPath),
                    "mdi mdi-database-eye-outline",
                    "连接池 / SQL / URI 监控（该页面有独立登录）"));
        }
        if (apiDocEnabled) {
            tools.add(tool("apidoc", "API 文档", apiDocUiPath,
                    "mdi mdi-api",
                    "前后端接口文档（Swagger UI，可填令牌直接调试）"));
        }
        // 系统指标始终可用（来自 /api/metrics，非可关闭项）
        tools.add(tool("metrics", "运行指标", "/api/metrics",
                "mdi mdi-chart-line",
                "Prometheus 文本指标：连接池 / JVM / 慢 SQL 计数"));

        return ApiResult.ok(tools);
    }

    /**
     * 把 Servlet 注册用的通配路径转成可点击的页面地址。
     * {@code /druid/*} → {@code /druid/}；{@code /druid/**} 同理。
     */
    private String normalizeDruidUrl(String pattern) {
        if (pattern == null || pattern.isEmpty()) return "/druid/";
        String p = pattern.trim();
        if (p.endsWith("**")) return p.substring(0, p.length() - 2);
        if (p.endsWith("*")) return p.substring(0, p.length() - 1);
        return p;
    }

    private Map<String, Object> tool(String key, String name, String url, String icon, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("url", url);
        m.put("icon", icon);
        m.put("description", description);
        // 这些是后端渲染的独立页面（非 SPA 路由），一律新标签页打开，
        // 避免把整个后台 SPA 导航走导致状态丢失
        m.put("newTab", true);
        return m;
    }

    /** 与其它后台接口同一口径：角色名包含「管理员」 */
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
