package com.culture.entity;

import lombok.Data;

import java.util.Date;

/**
 * 发件账号（sys_mail_account）。
 * 可配置多个账号，发送时按 sortOrder 顺序挑选当日未超限的账号，超限自动切换下一个。
 */
@Data
public class MailAccount {
    private Long id;
    private String username;
    private String password;
    private Integer dailyLimit;
    private Integer sentToday;
    private java.sql.Date sentDate;
    private Integer enabled;
    private Integer sortOrder;
    private Date createdAt;
    private Date updatedAt;
}
