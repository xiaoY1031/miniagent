package com.example.harness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HarnessApplication {
    /** 启动 Spring Boot 应用；args 为启动参数，例如 --server.port=8081。 */
    public static void main(String[] args) {
        SpringApplication.run(HarnessApplication.class, args);
    }
}
