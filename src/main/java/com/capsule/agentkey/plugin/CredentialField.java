package com.capsule.agentkey.plugin;

/**
 * 安装表单的一个凭证输入框定义，驱动前端 schema-driven 渲染。
 *
 * @param name        环境变量名，如 GITHUB_TOKEN（安装时注入子进程环境）
 * @param label       展示标签，如 "GitHub Token"
 * @param description 填写说明
 * @param required    是否必填
 * @param secret      true → 前端密码框 + 后端 AES 加密落库
 * @param placeholder 输入框占位提示
 */
public record CredentialField(
        String name,
        String label,
        String description,
        boolean required,
        boolean secret,
        String placeholder
) {
}
