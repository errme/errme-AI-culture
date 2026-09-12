package com.culture.entity;

import lombok.Data;

import java.util.Date;

/**
 * 邮件发送配置（sys_mail_config）。
 * 可在后台管理页动态修改发件账号/密码/模板/每日上限，支持多账号轮换。
 */
@Data
public class MailConfig {
    private Long id;
    private String host;
    private Integer port;
    private String username;
    private String password;
    private String fromName;
    private String subjectTemplate;
    private String bodyTemplate;
    private Integer dailyLimit;
    private Integer sentToday;
    private java.sql.Date sentDate;
    private Date updatedAt;
}
