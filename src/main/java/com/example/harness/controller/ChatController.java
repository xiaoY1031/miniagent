package com.example.harness.controller;

import com.example.harness.llm.QwenService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final QwenService qwenService;

    /** 由 Spring 注入千问服务，通过 runAgent 执行智能体循环。 */
    public ChatController(QwenService qwenService) {
        this.qwenService = qwenService;
    }

    /** 接收 message，执行工具并将结果交回模型，将最终回答封装为 answer 返回。 */
    @PostMapping
    public Map<String, String> chat(
            @RequestBody ChatRequest request) {

        String result = qwenService.runAgent(request.message());
        return Map.of("answer", result);
    }

    public record ChatRequest(String message) {
    }
}
