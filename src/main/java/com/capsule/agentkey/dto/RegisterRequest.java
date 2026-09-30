package com.capsule.agentkey.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank(message = "用户名不能为空")//这个注解的含义是字符串不能为null
    @Size(min = 3, max = 20, message = "用户名长度3-20位")//限制字符串长度在3到20个字符之间
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母数字下划线")//使用正则表达式限制内容，只允许英文字母，数字和下划线0
    private String username;

    @NotBlank(message = "密码不能为空")
    private String encryptedPassword;

    @Email(message = "邮箱格式不正确")//自动校验字符串是否符合标准的电子邮件格式
    private String email;
}
