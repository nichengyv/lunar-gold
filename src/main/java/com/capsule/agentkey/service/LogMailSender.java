package com.capsule.agentkey.service;

import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * 未配置 SMTP 时的兜底实现。
 *
 * 只有显式开启 app.mail.log-codes（仅开发环境）才把验证码打到日志；
 * 其余环境直接失败，避免生产「看起来发了邮件、实际没人收到」的静默故障。
 */
@Slf4j
public class LogMailSender implements MailSender {

    private final boolean logCodes;

    public LogMailSender(boolean logCodes) {
        this.logCodes = logCodes;
    }

    @Override
    public void sendVerificationCode(String to, String sceneLabel, String code, Duration ttl) {
        if (!logCodes) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "邮件服务未配置，暂时无法发送验证码");
        }
        log.info("[MAIL:LOG-ONLY] to={} scene={} code={} ttl={}min", to, sceneLabel, code, ttl.toMinutes());
    }
}
