package com.capsule.agentkey.service;

import java.time.Duration;

/**
 * 验证码邮件发送抽象。
 *
 * 存在两个实现：配置了 SMTP 时走 {@link SmtpMailSender}；
 * 未配置时走 {@link LogMailSender}（仅开发环境允许把验证码打到日志）。
 * 抽成接口是为了让 EmailCodeService 不感知具体投递方式，也便于测试替换。
 */
public interface MailSender {

    /**
     * 发送验证码邮件。
     *
     * @param to         收件邮箱
     * @param sceneLabel 场景文案（注册 / 重置密码）
     * @param code       6 位数字验证码
     * @param ttl        有效期
     */
    void sendVerificationCode(String to, String sceneLabel, String code, Duration ttl);
}
