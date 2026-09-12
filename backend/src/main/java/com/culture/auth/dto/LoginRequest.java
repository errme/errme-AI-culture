package com.culture.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** 登录请求体 */
public class LoginRequest {

    // 支持用户名或邮箱登录，因此不做 @Email 强校验
    @NotBlank(message = "账号不能为空")
    private String email;

    @NotBlank(message = "密码不能为空")
    private String password;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
