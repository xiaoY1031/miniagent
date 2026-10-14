package com.example.harness.controller;

import com.example.harness.llm.QwenService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final QwenService qwenService;

    /** 由 Spring 注入千问服务，供聊天接口使用。 */
    public ChatController(QwenService qwenService) {
        this.qwenService = qwenService;
    }

    /** 接收 message，执行单次模型请求及第一个工具调用，将结果封装为 answer 返回。 */
    @PostMapping
    public Map<String, String> chat(
            @RequestBody ChatRequest request) {

        String result = qwenService.chatWithTools(request.message());
        return Map.of("answer", result);
    }

    public record ChatRequest(String message) {
    }
}
