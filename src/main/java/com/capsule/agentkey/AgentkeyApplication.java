package com.capsule.agentkey;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class AgentkeyApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentkeyApplication.class, args);
    }
}
