package com.culture.config;

import com.culture.api.JwtAuthFilter;
import com.culture.entity.OperationLog;
import com.culture.entity.User;
import com.culture.service.OperationLogService;
import com.culture.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 操作日志拦截器（审计）。
 *
 * <p>记录规则：</p>
 * <ul>
 *   <li>仅 URI 以 <code>/api/admin/</code> 开头，且方法为 POST/PUT/DELETE（GET 查询不记录）；</li>
 *   <li>module 取路径第三段（/api/admin/culture/save → culture）；</li>
 *   <li>action 取最后一段中的 save/delete/audit/upload，取不到就用最后一段本身；</li>
 *   <li>targetId 先看控制器是否写了请求属性 oplogTargetId（JSON body 拦截器读不到），
 *       再退化为请求参数 id / ids，仍取不到就留空；</li>
 *   <li>success 看 afterCompletion 的异常是否为 null；costMs 用 preHandle 记下的开始时间。</li>
 * </ul>
 *
 * <p><b>硬性要求：日志记录失败绝不能影响主流程</b>，因此 preHandle/afterCompletion 内
 * 全部 try/catch 吞掉并打印。</p>
 */
@Component
public class OperationLogInterceptor implements HandlerInterceptor {

    /** 请求属性：开始时间（毫秒） */
    private static final String ATTR_START = "oplogStartTime";
    /** 请求属性：控制器主动告知的目标 id */
    private static final String ATTR_TARGET_ID = "oplogTargetId";

    private static final String[] ACTIONS = {"save", "delete", "audit", "upload"};

    private static final int MAX_MODULE_LEN = 50;
    private static final int MAX_ACTION_LEN = 50;
    private static final int MAX_TARGET_LEN = 64;
    private static final int MAX_DETAIL_LEN = 500;
    private static final int MAX_URI_LEN = 255;
    private static final int MAX_IP_LEN = 64;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private UserService userService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            request.setAttribute(ATTR_START, System.currentTimeMillis());
        } catch (Exception e) {
            // 忽略：计时失败不影响请求
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            String uri = request.getRequestURI();
            String method = request.getMethod();
            if (uri == null || !uri.startsWith("/api/admin/")) return;
            if (!"POST".equalsIgnoreCase(method)
                    && !"PUT".equalsIgnoreCase(method)
                    && !"DELETE".equalsIgnoreCase(method)) {
                return;   // GET 查询不记录
            }

            String module = moduleOf(uri);
            String last = lastSegment(uri);
            String action = actionOf(last);

            Long userId = null;
            Object uid = request.getAttribute(JwtAuthFilter.ATTR_LOGIN_USER_ID);
            if (uid instanceof Long) userId = (Long) uid;

            String username = null;
            if (userId != null) {
                try {
                    User u = userService.findById(userId);
                    if (u != null) username = u.getUsername();
                } catch (Exception ignore) {
                    // 用户查不到不影响日志写入
                }
            }

            String targetId = targetIdOf(request);
            String detail = module + "." + action + (isEmpty(targetId) ? "" : "#" + targetId);

            Long costMs = null;
            Object start = request.getAttribute(ATTR_START);
            if (start instanceof Long) {
                costMs = System.currentTimeMillis() - (Long) start;
            }

            OperationLog log = new OperationLog();
            log.setUserId(userId);
            log.setUsername(truncate(username, 50));
            log.setModule(truncate(module, MAX_MODULE_LEN));
            log.setAction(truncate(action, MAX_ACTION_LEN));
            log.setTargetId(truncate(targetId, MAX_TARGET_LEN));
            log.setDetail(truncate(detail, MAX_DETAIL_LEN));
            log.setMethod(method);
            log.setUri(truncate(uri, MAX_URI_LEN));
            log.setIp(truncate(clientIp(request), MAX_IP_LEN));
            log.setSuccess(ex == null ? 1 : 0);
            log.setCostMs(costMs);

            operationLogService.record(log);
        } catch (Throwable t) {
            // 审计失败绝不影响业务请求
            System.out.println("[oplog] 记录操作日志失败：" + t.getMessage());
            t.printStackTrace();
        }
    }

    /** module = 路径第三段（/api/admin/{module}/...） */
    private String moduleOf(String uri) {
        String[] parts = uri.split("/");
        // ["", "api", "admin", module, action...]
        if (parts.length > 3 && !parts[3].isEmpty()) return parts[3];
        return "admin";
    }

    /** 最后一段路径（去掉可能存在的结尾斜杠） */
    private String lastSegment(String uri) {
        String u = uri;
        while (u.endsWith("/") && u.length() > 1) u = u.substring(0, u.length() - 1);
        int idx = u.lastIndexOf('/');
        return idx < 0 ? u : u.substring(idx + 1);
    }

    /** action：最后一段里出现 save/delete/audit/upload 就用它，否则用最后一段本身 */
    private String actionOf(String last) {
        if (last == null || last.isEmpty()) return "unknown";
        String lower = last.toLowerCase();
        for (String a : ACTIONS) {
            if (lower.contains(a)) return a;
        }
        return last;
    }

    /**
     * 目标 id：优先控制器写入的请求属性；其次请求参数 id / ids。
     * JSON body 在拦截器阶段已被读取，无法再解析，故 body 内 id 由控制器主动回填。
     */
    private String targetIdOf(HttpServletRequest request) {
        Object attr = request.getAttribute(ATTR_TARGET_ID);
        if (attr != null) return String.valueOf(attr);
        String[] keys = {"id", "ids", "userId"};
        for (String k : keys) {
            try {
                String v = request.getParameter(k);
                if (v != null && !v.isEmpty()) return v;
            } catch (Exception ignore) {
                // 参数解析失败忽略
            }
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String h : headers) {
            String v = request.getHeader(h);
            if (v != null && !v.isEmpty() && !"unknown".equalsIgnoreCase(v)) {
                int comma = v.indexOf(',');
                return (comma > 0 ? v.substring(0, comma) : v).trim();
            }
        }
        return request.getRemoteAddr();
    }

    private boolean isEmpty(String s) {
        return s == null || s.isEmpty() || "null".equals(s);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
