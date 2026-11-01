package com.capsule.agentkey;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing//开启JPA自动审计功能//配套entity层的@CreateDate注解和@LastModfiedDate注解
public class AgentkeyApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AgentkeyApplication.class);
        // 关闭 headless：选择工作区文件夹需要在后端弹出系统原生资源管理器（FolderPickerService）。
        // 注意这不是 application.yml 里的 spring.main.headless —— Spring Boot 没有这个配置项，
        // 只能通过 setHeadless(false) 或 -Djava.awt.headless=false 生效。
        app.setHeadless(false);
        app.run(args);
    }
}
