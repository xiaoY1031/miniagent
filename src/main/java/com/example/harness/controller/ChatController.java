package com.example.harness.controller;

import com.example.harness.agent.AgentLoop;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentLoop agentLoop;

    /** 由 Spring 注入智能体循环，供聊天接口执行模型与工具交互。 */
    public ChatController(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    /** 接收 message，执行工具并将结果交回模型，将最终回答封装为 answer 返回。 */
    @PostMapping
    public Map<String, String> chat(
            @RequestBody ChatRequest request) {

        String result = agentLoop.run(request.message());
        return Map.of("answer", result);
    }

    public record ChatRequest(String message) {
    }
}
