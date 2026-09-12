package com.culture.auth.service;

import com.culture.entity.MailAccount;
import com.culture.entity.MailConfig;
import com.culture.entity.MailLog;
import com.culture.mapper.MailAccountMapper;
import com.culture.mapper.MailConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

/**
 * 验证码邮件服务（多账号轮换）。
 * - 通用设置（SMTP 服务器/模板/默认上限）读取自 sys_mail_config；
 * - 发件账号可配置多个（sys_mail_account），一行一个账号；
 *   发送时按顺序挑选"当日未超限"的账号，达到上限自动切换下一个；
 * - 单账号每日限流（默认 450 封），跨天自动重置；
 * - 每次发送写 sys_mail_log（成功/失败 + 使用的账号）；
 * - 默认 587 STARTTLS；端口为 465 时自动切 SSL。
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final MailConfigMapper configMapper;
    private final MailAccountMapper accountMapper;

    public MailService(MailConfigMapper configMapper, MailAccountMapper accountMapper) {
        this.configMapper = configMapper;
        this.accountMapper = accountMapper;
    }

    /**
     * 发送前校验：存在可用（未超限）账号。
     * 供 AuthService 在发放验证码前调用，全部超限时直接提示。
     */
    public void assertCanSend() {
        MailAccount account = pickAccount();
        if (account == null) {
            throw new BusinessException("今日所有发件账号均已达上限，请到后台邮件设置添加或更换账号");
        }
    }

    /**
     * 发送验证码邮件（模板来自后台配置，占位符：{code} {minutes}）。
     */
    public void sendCode(String to, String code, int expireMinutes) throws Exception {
        MailConfig c = configMapper.getConfig();
        MailAccount account = pickAccount();
        if (account == null) {
            throw new BusinessException("今日所有发件账号均已达上限，请到后台邮件设置添加或更换账号");
        }
        String subject = c.getSubjectTemplate();
        String body = (c.getBodyTemplate() == null ? "" : c.getBodyTemplate())
                .replace("{code}", code)
                .replace("{minutes}", String.valueOf(expireMinutes));
        send(account, to, subject, body, "verify");
    }

    /** 发送测试邮件（用第一个可用账号） */
    public void sendTest(String to) throws Exception {
        MailAccount account = pickAccount();
        if (account == null) {
            throw new BusinessException("今日所有发件账号均已达上限，请到后台邮件设置添加或更换账号");
        }
        send(account, to, "【遇你】邮件测试", "这是一封测试邮件，说明邮件服务配置正确。", "test");
    }

    /** 挑选可用账号：按顺序找当日未超限的，跨天自动重置计数 */
    private MailAccount pickAccount() {
        LocalDate today = LocalDate.now();
        List<MailAccount> accounts = accountMapper.listEnabledAccounts();
        for (MailAccount a : accounts) {
            if (a.getSentDate() == null || !a.getSentDate().toLocalDate().equals(today)) {
                accountMapper.resetSent(a.getId(), Date.valueOf(today));
                a.setSentToday(0);
                a.setSentDate(Date.valueOf(today));
            }
            int limit = a.getDailyLimit() == null ? 450 : a.getDailyLimit();
            int sent = a.getSentToday() == null ? 0 : a.getSentToday();
            if (sent < limit) {
                return a;
            }
        }
        return null;
    }

    /** 实际发送 + 计数 + 日志 */
    private void send(MailAccount account, String to, String subject, String body, String scene) throws Exception {
        MailConfig c = configMapper.getConfig();
        JavaMailSenderImpl sender = buildSender(c, account);
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        helper.setFrom(account.getUsername(), c.getFromName() == null ? "" : c.getFromName());
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(body, false);
        try {
            sender.send(message);
            // 发送成功：该账号今日计数 +1，写成功日志
            accountMapper.incrementSent(account.getId());
            insertLog(to, scene, subject, 1, "");
            log.info("邮件已发送至 {}（账号 {}）", to, account.getUsername());
        } catch (Exception e) {
            insertLog(to, scene, subject, 0, truncate(e.getMessage(), 480));
            log.error("邮件发送失败至 {}（账号 {}）: {}", to, account.getUsername(), e.getMessage());
            throw e;
        }
    }

    private void insertLog(String to, String scene, String subject, int status, String error) {
        try {
            MailLog l = new MailLog();
            l.setToEmail(to);
            l.setScene(scene);
            l.setSubject(subject);
            l.setStatus(status);
            l.setErrorMsg(error);
            configMapper.insertLog(l);
        } catch (Exception ignored) {
            // 日志写入失败不影响主流程
        }
    }

    /** 构建发件器：默认 587 STARTTLS；端口 465 时 SSL */
    private JavaMailSenderImpl buildSender(MailConfig c, MailAccount account) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(c.getHost());
        sender.setPort(c.getPort());
        sender.setUsername(account.getUsername());
        sender.setPassword(account.getPassword());
        sender.setProtocol("smtp");
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.host", c.getHost());
        props.put("mail.smtp.port", String.valueOf(c.getPort()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "15000");
        if (c.getPort() != null && c.getPort() == 465) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.ssl.trust", "*");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
            props.put("mail.smtp.ssl.trust", "*");
        }
        return sender;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
