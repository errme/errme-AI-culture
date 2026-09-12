package com.culture.entity;

import lombok.Data;

import java.util.Date;

/**
 * 邮件发送日志（sys_mail_log）。
 */
@Data
public class MailLog {
    private Long id;
    private String toEmail;
    private String scene;
    private String subject;
    private Integer status;   // 1成功 0失败
    private String errorMsg;
    private Date createdAt;
}
