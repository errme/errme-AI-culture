package com.culture.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** 发送验证码请求体 */
public class SendCodeRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 场景：register（注册，默认）| forgot（忘记密码） */
    private String scene = "register";

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }
}
