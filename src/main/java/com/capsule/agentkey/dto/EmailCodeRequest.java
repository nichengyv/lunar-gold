package com.capsule.agentkey.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发送邮箱验证码请求。
 * scene 取 REGISTER（注册）/ RESET（重置密码），由 AuthController 解析。
 */
@Data
public class EmailCodeRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "验证码场景不能为空")
    private String scene;
}
