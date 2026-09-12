package com.culture.api;

import com.culture.auth.service.BusinessException;
import com.culture.auth.service.MailService;
import com.culture.entity.MailAccount;
import com.culture.entity.MailConfig;
import com.culture.entity.MailLog;
import com.culture.entity.Role;
import com.culture.mapper.MailAccountMapper;
import com.culture.mapper.MailConfigMapper;
import com.culture.service.RoleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * 后台邮件管理 API（JWT + 管理员）：
 * - 通用设置：SMTP 服务器/端口/发件人/模板（sys_mail_config 一行）
 * - 多发件账号：一行一个账号（sys_mail_account），达到每日上限自动切换下一个
 * - 统计/日志/测试发送
 */
@RestController
@RequestMapping("/api/admin/mail")
public class MailAdminController {

    @Autowired
    private MailConfigMapper configMapper;
    @Autowired
    private MailAccountMapper accountMapper;
    @Autowired
    private MailService mailService;
    @Autowired
    private RoleService roleService;

    private boolean isAdmin(Long userId) {
        if (userId == null) return false;
        for (Role r : roleService.listRoleByUserId(userId)) {
            if ("管理员".equals(r.getName())) return true;
        }
        return false;
    }

    private void checkAdmin(HttpServletRequest request) {
        Long uid = (Long) request.getAttribute("loginUserId");
        if (!isAdmin(uid)) {
            throw new BusinessException("无权限");
        }
    }

    /** 通用设置（SMTP/模板） */
    @GetMapping("/config")
    public ApiResult<Map<String, Object>> config(HttpServletRequest request) {
        checkAdmin(request);
        MailConfig c = configMapper.getConfig();
        Map<String, Object> data = new LinkedHashMap<>();
        if (c == null) {
            data.put("configured", false);
        } else {
            data.put("configured", true);
            data.put("id", c.getId());
            data.put("host", c.getHost());
            data.put("port", c.getPort());
            data.put("fromName", c.getFromName());
            data.put("subjectTemplate", c.getSubjectTemplate());
            data.put("bodyTemplate", c.getBodyTemplate());
            data.put("defaultLimit", c.getDailyLimit());
        }
        return ApiResult.ok(data);
    }

    /** 修改通用设置 */
    @PostMapping("/config")
    public ApiResult<Void> updateConfig(@RequestBody MailConfig input, HttpServletRequest request) {
        checkAdmin(request);
        if (input.getHost() == null || input.getHost().trim().isEmpty()) {
            return ApiResult.error("SMTP 服务器不能为空");
        }
        MailConfig cur = configMapper.getConfig();
        if (cur == null) return ApiResult.error("邮件配置不存在，请先初始化数据库");
        input.setId(cur.getId());
        input.setUsername(cur.getUsername());
        input.setPassword(cur.getPassword());
        input.setSentToday(cur.getSentToday());
        input.setSentDate(cur.getSentDate());
        // 空值兜底：未提交的字段保留原值（daily_limit 非空约束）
        if (input.getPort() == null) input.setPort(cur.getPort());
        if (input.getFromName() == null) input.setFromName(cur.getFromName());
        if (input.getSubjectTemplate() == null) input.setSubjectTemplate(cur.getSubjectTemplate());
        if (input.getBodyTemplate() == null) input.setBodyTemplate(cur.getBodyTemplate());
        if (input.getDailyLimit() == null) input.setDailyLimit(cur.getDailyLimit());
        configMapper.updateConfig(input);
        return ApiResult.ok("保存成功", null);
    }

    /** 账号列表（密码打码） */
    @GetMapping("/accounts")
    public ApiResult<List<Map<String, Object>>> accounts(HttpServletRequest request) {
        checkAdmin(request);
        List<Map<String, Object>> list = new ArrayList<>();
        for (MailAccount a : accountMapper.listAccounts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("username", a.getUsername());
            m.put("passwordMasked", "******");
            m.put("dailyLimit", a.getDailyLimit());
            m.put("sentToday", a.getSentToday());
            m.put("sentDate", a.getSentDate() == null ? null : a.getSentDate().toString());
            m.put("enabled", a.getEnabled());
            m.put("sortOrder", a.getSortOrder());
            list.add(m);
        }
        return ApiResult.ok(list);
    }

    /** 批量保存账号（有 id 更新，无 id 新增；密码留空=不修改） */
    @PostMapping("/accounts")
    public ApiResult<Void> saveAccounts(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        checkAdmin(request);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("accounts");
        if (rows == null || rows.isEmpty()) return ApiResult.ok(null);
        int order = 0;
        for (Map<String, Object> row : rows) {
            String username = str(row.get("username"));
            if (username == null || username.trim().isEmpty()) continue;
            MailAccount a = new MailAccount();
            a.setUsername(username.trim());
            a.setPassword(str(row.get("password")));
            a.setDailyLimit(intv(row.get("dailyLimit"), 450));
            a.setEnabled(boolv(row.get("enabled")) ? 1 : 0);
            a.setSortOrder(order++);
            if (row.get("id") != null) {
                a.setId(Long.valueOf(str(row.get("id"))));
                MailAccount old = accountMapper.findById(a.getId());
                if (old == null) continue;
                if (a.getPassword() == null || a.getPassword().trim().isEmpty()) {
                    a.setPassword(old.getPassword());
                }
                accountMapper.updateAccount(a);
            } else {
                if (a.getPassword() == null || a.getPassword().trim().isEmpty()) {
                    return ApiResult.error("新账号必须填写密码");
                }
                accountMapper.insertAccount(a);
            }
        }
        return ApiResult.ok("保存成功", null);
    }

    /** 删除账号 */
    @DeleteMapping("/accounts/{id}")
    public ApiResult<Void> deleteAccount(@PathVariable Long id, HttpServletRequest request) {
        checkAdmin(request);
        accountMapper.deleteAccount(id);
        return ApiResult.ok("已删除", null);
    }

    /** 统计：全部账号汇总 + 各账号明细 */
    @GetMapping("/stats")
    public ApiResult<Map<String, Object>> stats(HttpServletRequest request) {
        checkAdmin(request);
        Date start = java.sql.Date.valueOf(java.time.LocalDate.now());
        Long success = configMapper.countByStatus(1, start);
        Long fail = configMapper.countByStatus(0, start);
        List<Map<String, Object>> accounts = new ArrayList<>();
        int totalSent = 0;
        int totalLimit = 0;
        for (MailAccount a : accountMapper.listAccounts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", a.getUsername());
            m.put("sentToday", a.getSentToday());
            m.put("dailyLimit", a.getDailyLimit());
            m.put("enabled", a.getEnabled());
            accounts.add(m);
            if (a.getEnabled() != null && a.getEnabled() == 1) {
                totalSent += a.getSentToday() == null ? 0 : a.getSentToday();
                totalLimit += a.getDailyLimit() == null ? 0 : a.getDailyLimit();
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sentToday", totalSent);
        data.put("dailyLimit", totalLimit);
        data.put("todaySuccess", success == null ? 0 : success);
        data.put("todayFail", fail == null ? 0 : fail);
        data.put("accounts", accounts);
        return ApiResult.ok(data);
    }

    /** 最近发送日志（50 条） */
    @GetMapping("/logs")
    public ApiResult<List<MailLog>> logs(HttpServletRequest request) {
        checkAdmin(request);
        return ApiResult.ok(configMapper.recentLogs(50));
    }

    /** 测试发送（自动使用第一个可用账号） */
    @PostMapping("/test")
    public ApiResult<Void> test(@RequestBody Map<String, String> body, HttpServletRequest request) {
        checkAdmin(request);
        String to = body.get("to");
        if (to == null || to.trim().isEmpty()) return ApiResult.error("请输入收件邮箱");
        try {
            mailService.sendTest(to.trim());
            return ApiResult.ok("测试邮件已发送至 " + to.trim(), null);
        } catch (Exception e) {
            return ApiResult.error("发送失败：" + e.getMessage());
        }
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
    private Integer intv(Object o, int def) { try { return o == null ? def : Integer.valueOf(str(o).trim()); } catch (Exception e) { return def; } }
    private boolean boolv(Object o) { return o != null && (Boolean.TRUE.equals(o) || "1".equals(str(o)) || "true".equalsIgnoreCase(str(o))); }
}
