package com.example.harness.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "qwen.api-key=test-key",
        "qwen.base-url=https://example.test/v1",
        "qwen.model=qwen-plus"
})
class ToolExecutorTest {

    @Autowired
    private ToolExecutor toolExecutor;

    @Test
    void shouldExecuteProjectInfoTool() {
        String result = toolExecutor.execute("get_project_info");

        System.out.println(result);

        assertTrue(result.contains("Spring Boot"));
        assertTrue(result.contains("Redis"));
    }
}
