package com.bite.aiwear;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AIWear 应用启动类，Spring Boot 入口。
 */
@SpringBootApplication
@Slf4j
public class AiWearApplication {

    public static void main(String[] args) {
        log.info("项目启动成功");
        SpringApplication.run(AiWearApplication.class, args);
    }

}
