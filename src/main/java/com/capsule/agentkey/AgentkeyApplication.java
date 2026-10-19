package com.capsule.agentkey;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing//开启JPA自动审计功能//配套entity层的@CreateDate注解和@LastModfiedDate注解
public class AgentkeyApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentkeyApplication.class, args);
    }
}
