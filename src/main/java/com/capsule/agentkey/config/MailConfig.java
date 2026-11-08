package com.capsule.agentkey.config;

import com.capsule.agentkey.service.LogMailSender;
import com.capsule.agentkey.service.MailSender;
import com.capsule.agentkey.service.SmtpMailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

/**
 * 邮件发送器装配：按是否配置 SMTP 主机选择实现。
 *
 * 不用 @ConditionalOnProperty 而在这里判断，是因为 application.yml 里
 * spring.mail.host 的默认值写了 ${MAIL_HOST:}——属性始终存在但可能为空串，
 * 条件注解会把空串当成「已配置」，从而装配出连不上的 SMTP 发送器。
 */
@Configuration
public class MailConfig {

    /**
     * Bean 名不能叫 mailSender：Boot 的 MailSenderAutoConfiguration 在配置了
     * spring.mail.host 时也会注册同名 bean（JavaMailSender），同名会直接启动失败。
     */
    @Bean
    public MailSender agentMailSender(ObjectProvider<JavaMailSender> javaMailSenderProvider,
                                      @Value("${spring.mail.host:}") String mailHost,
                                      @Value("${spring.mail.username:}") String mailUsername,
                                      @Value("${app.mail.log-codes:false}") boolean logCodes) {
        JavaMailSender smtp = javaMailSenderProvider.getIfAvailable();
        if (StringUtils.hasText(mailHost) && smtp != null) {
            return new SmtpMailSender(smtp, mailUsername);
        }
        return new LogMailSender(logCodes);
    }
}
