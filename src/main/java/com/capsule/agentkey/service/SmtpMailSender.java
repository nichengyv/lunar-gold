package com.capsule.agentkey.service;

import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * SMTP 投递实现：仅在配置了 spring.mail.host 时由 MailConfig 创建。
 *
 * 发送失败统一转成 500 语义的业务异常——此时是服务端投递故障，
 * 与「验证码输错」这类用户输入问题必须区分开。
 */
@Slf4j
public class SmtpMailSender implements MailSender {

    private final JavaMailSender javaMailSender;
    private final String from;

    public SmtpMailSender(JavaMailSender javaMailSender, String from) {
        this.javaMailSender = javaMailSender;
        this.from = from;
    }

    @Override
    public void sendVerificationCode(String to, String sceneLabel, String code, Duration ttl) {
        SimpleMailMessage message = new SimpleMailMessage();
        // 未配置 MAIL_USERNAME 时交给 JavaMailSender 的默认 From，避免设置空字符串
        if (StringUtils.hasText(from)) {
            message.setFrom(from);
        }
        message.setTo(to);
        message.setSubject("AgentKey " + sceneLabel + "验证码");
        message.setText("你的验证码是：" + code + "\n\n"
                + ttl.toMinutes() + " 分钟内有效。如非本人操作，请忽略本邮件。");
        try {
            javaMailSender.send(message);
            log.info("验证码邮件已发送 [to={}, scene={}]", to, sceneLabel);
        } catch (MailException e) {
            log.error("验证码邮件发送失败 [to={}, scene={}]: {}", to, sceneLabel, e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "验证码邮件发送失败，请稍后重试");
        }
    }
}
