package com.capsule.agentkey.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 邮箱验证码找回密码请求。
 * encryptedPassword 为前端 RSA 公钥加密后的密文，与登录/注册保持一致。
 */
@Data
public class ResetPasswordRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "验证码不能为空")
    private String emailCode;

    @NotBlank(message = "新密码不能为空")
    private String encryptedPassword;
}
