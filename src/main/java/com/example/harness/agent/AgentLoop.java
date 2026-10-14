package com.example.harness.agent;

import com.example.harness.llm.QwenService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AgentLoop {

    private final QwenService qwenService;

    /** 由 Spring 注入千问服务，供后续智能体循环请求模型。 */
    public AgentLoop(QwenService qwenService) {
        this.qwenService = qwenService;
    }

    /**
     * 根据对话记录请求模型，判断第一条消息是否包含工具调用。
     * 返回 true 表示需要执行工具，false 表示模型没有请求工具；本阶段不执行工具。
     */
    public boolean needsToolCall(List<Map<String, Object>> messages) {
        JsonNode response = qwenService.getFinalAnswer(messages);
        if (response == null || !response.path("choices").isArray()
                || response.path("choices").isEmpty()) {
            throw new IllegalStateException("千问 API 没有返回有效 choice");
        }

        JsonNode choice = response.path("choices").get(0);
        JsonNode message = choice.path("message");
        if (!message.isObject()) {
            throw new IllegalStateException("千问 API 返回的 message 格式无效");
        }
        JsonNode toolCalls = message.path("tool_calls");
        if (!toolCalls.isMissingNode() && !toolCalls.isNull() && !toolCalls.isArray()) {
            throw new IllegalStateException("千问返回的 tool_calls 格式无效");
        }

        if (!toolCalls.isMissingNode() && !toolCalls.isNull() && !toolCalls.isEmpty()) {
            // 模型请求调用工具
            return true;
        } else {
            // 模型没有请求调用工具
            return false;
        }
    }
}

