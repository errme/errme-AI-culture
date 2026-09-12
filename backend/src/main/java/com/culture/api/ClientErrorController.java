package com.culture.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 前端错误上报（浏览器端未捕获异常 / 未处理 Promise 拒绝）
 *
 * <p>POST /api/client-error，免登录、<b>永远返回 200</b>：上报失败绝不能影响页面。
 * 为避免日志被刷爆：单条截断到 1000 字符，并用 Redis 按 IP 限流（默认每分钟 20 条），
 * Redis 不可用时降级为「只记录、不限流」。</p>
 */
@RestController
@RequestMapping("/api")
public class ClientErrorController {

    private static final Logger log = LoggerFactory.getLogger(ClientErrorController.class);
    private static final int MAX_LEN = 1000;
    private static final int LIMIT_PER_MINUTE = 20;

    /** 后端最多保留多少条前端错误（环形列表） */
    private static final int MAX_STORED = 200;

    private final StringRedisTemplate redisTemplate;
    private final com.culture.service.RoleService roleService;

    public ClientErrorController(ObjectProvider<StringRedisTemplate> redisProvider,
                                 ObjectProvider<com.culture.service.RoleService> roleProvider) {
        this.redisTemplate = redisProvider.getIfAvailable();
        this.roleService = roleProvider.getIfAvailable();
    }

    @PostMapping("/client-error")
    public Map<String, Object> report(@RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        try {
            String ip = clientIp(request);
            if (!allowed(ip)) {
                return ok("ignored");
            }
            String msg = cut(str(body, "message"), 300);
            String source = cut(str(body, "source"), 200);
            String stack = cut(str(body, "stack"), MAX_LEN);
            String url = cut(str(body, "url"), 300);
            String ua = cut(str(body, "userAgent"), 200);
            String line = str(body, "line");
            String col = str(body, "col");

            log.warn("[client-error] ip={} url={} msg={} at {}({},{}) ua={} stack={}",
                    ip, url, msg, source, line, col, ua, stack);
            // 落一份到 Redis（环形列表，最多 200 条）+ 累计计数：
            // 只有日志的话，出问题时得翻服务器文件；这里让后台可以直接看最近错误（GET /api/admin/client-errors）
            remember(ip, url, msg, source, line, col, ua);
        } catch (Exception e) {
            // 上报接口自身出错也只记一行，不向外抛
            log.warn("[client-error] 处理上报失败：{}", e.getMessage());
        }
        return ok("ok");
    }

    /**
     * 写入 Redis 环形列表 + 计数（失败一律忽略：上报是尽力而为，不能影响接口返回）。
     * key：client:errors（列表，LTRIM 保留最近 MAX_STORED 条）、client:error:total（累计计数，供 /api/metrics 用）。
     * 用 Jackson 序列化，避免手拼 JSON 时被引号/换行破坏。
     */
    private void remember(String ip, String url, String msg, String source, String line, String col, String ua) {
        if (redisTemplate == null) return;
        try {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("at", java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            item.put("ip", ip);
            item.put("url", url);
            item.put("msg", msg);
            item.put("source", source + "(" + line + "," + col + ")");
            item.put("ua", ua);
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(item);
            redisTemplate.opsForList().leftPush("client:errors", json);
            redisTemplate.opsForList().trim("client:errors", 0, MAX_STORED - 1);
            redisTemplate.opsForValue().increment("client:error:total");
        } catch (Exception e) {
            log.debug("[client-error] 写入 Redis 失败（忽略）：{}", e.getMessage());
        }
    }

    /** 最近的前端错误（管理员）：GET /api/admin/client-errors?limit=50 */
    @org.springframework.web.bind.annotation.GetMapping("/admin/client-errors")
    public Map<String, Object> recent(@org.springframework.web.bind.annotation.RequestParam(value = "limit", required = false) Integer limit,
                                      javax.servlet.http.HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (!isAdmin(userId)) return err(403, "无权限");
        int size = (limit == null || limit < 1) ? 50 : Math.min(limit, MAX_STORED);
        java.util.List<String> items = new java.util.ArrayList<>();
        long total = 0L;
        if (redisTemplate != null) {
            try {
                java.util.List<String> raw = redisTemplate.opsForList().range("client:errors", 0, size - 1);
                if (raw != null) items.addAll(raw);
                String t = redisTemplate.opsForValue().get("client:error:total");
                total = t == null ? 0L : Long.parseLong(t);
            } catch (Exception e) {
                log.warn("[client-error] 读取失败：{}", e.getMessage());
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("rows", items);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("code", 200);
        res.put("message", "操作成功");
        res.put("data", data);
        return res;
    }

    private Long currentUserId(javax.servlet.http.HttpServletRequest request) {
        Object v = request.getAttribute(com.culture.api.JwtAuthFilter.ATTR_LOGIN_USER_ID);
        if (v instanceof Long) return (Long) v;
        if (v == null) return null;
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private boolean isAdmin(Long userId) {
        if (userId == null || roleService == null) return false;
        try {
            for (com.culture.entity.Role r : roleService.listRoleByUserId(userId)) {
                if ("管理员".equals(r.getName())) return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private Map<String, Object> err(int code, String message) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("code", code);
        res.put("message", message);
        res.put("data", null);
        return res;
    }

    private Map<String, Object> ok(String message) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("code", 200);
        res.put("message", message);
        res.put("data", null);
        return res;
    }

    /** Redis 限流：同一 IP 每分钟最多 LIMIT_PER_MINUTE 条；Redis 不可用则放行 */
    private boolean allowed(String ip) {
        if (redisTemplate == null) return true;
        try {
            String key = "client:err:" + ip;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, 60, TimeUnit.SECONDS);
            }
            return count == null || count <= LIMIT_PER_MINUTE;
        } catch (Exception e) {
            return true;
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.trim().isEmpty()) return real.trim();
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private String str(Map<String, Object> body, String key) {
        if (body == null) return "";
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private String cut(String value, int max) {
        if (value == null) return "";
        String one = value.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= max ? one : one.substring(0, max);
    }
}
