package com.example.harness.controller;

import com.example.harness.llm.QwenService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final QwenService qwenService;

    public ChatController(QwenService qwenService) {
        this.qwenService = qwenService;
    }

    @PostMapping
    public Map<String, String> chat(
            @RequestBody ChatRequest request) {

        String result = qwenService.chatWithTools(request.message());
        return Map.of("answer", result);
    }

    public record ChatRequest(String message) {
    }
}
