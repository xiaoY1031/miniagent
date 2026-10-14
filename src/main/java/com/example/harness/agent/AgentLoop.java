package com.example.harness.agent;

import com.example.harness.llm.QwenService;
import com.example.harness.tool.ToolExecutor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@Component
public class AgentLoop {

    private final QwenService qwenService;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;
    private static final int MAX_TOOL_ROUNDS = 4;

    /** 由 Spring 注入模型服务、工具执行器和 JSON 转换器，供智能体循环使用。 */
    public AgentLoop(QwenService qwenService, ToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.qwenService = qwenService;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 接收用户问题，循环请求模型并执行工具，直到模型返回非空回答正文。
     * 每个请求使用独立对话记录，最多执行四轮工具；超过上限时明确报错。
     */
    public String run(String question) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", question));
        int toolRounds = 0;
        while (true) {
            JsonNode message = extractMessage(qwenService.getFinalAnswer(messages));
            JsonNode toolCalls = message.path("tool_calls");
            if (!hasToolCalls(toolCalls)) {
                JsonNode content = message.path("content");
                if (!content.isTextual() || content.asText().isBlank()) {
                    throw new IllegalStateException("千问 API 没有返回有效回答正文");
                }
                return content.asText();
            }
            if (toolRounds >= MAX_TOOL_ROUNDS) {
                throw new IllegalStateException("工具调用超过最大轮数：" + MAX_TOOL_ROUNDS);
            }

            // 先保存模型的原始调用消息，再为每个工具追加匹配 ID 的执行结果。
            messages.add(objectMapper.convertValue(message, new TypeReference<Map<String, Object>>() {}));
            for (JsonNode toolCall : toolCalls) {
                String toolCallId = toolCall.path("id").asText();
                if (toolCallId.isBlank() || !"function".equals(toolCall.path("type").asText())) {
                    throw new IllegalStateException("工具调用缺少 ID 或类型无效");
                }
                String toolName = toolCall.path("function").path("name").asText();
                String result = toolExecutor.execute(toolName);
                messages.add(Map.of("role", "tool", "tool_call_id", toolCallId, "content", result));
            }
            toolRounds++;
            // 带着新增的工具结果进入下一轮，由模型决定继续调用工具还是回答。
        }
    }

    /**
     * 根据对话记录请求模型，判断第一条消息是否包含工具调用。
     * 返回 true 表示需要执行工具，false 表示模型没有请求工具；此方法本身不执行工具。
     */
    public boolean needsToolCall(List<Map<String, Object>> messages) {
        JsonNode message = extractMessage(qwenService.getFinalAnswer(messages));
        return hasToolCalls(message.path("tool_calls"));
    }

    /** 检查完整模型响应的结构，返回第一条 assistant 消息。 */
    private JsonNode extractMessage(JsonNode response) {
        if (response == null || !response.path("choices").isArray()
                || response.path("choices").isEmpty()) {
            throw new IllegalStateException("千问 API 没有返回有效 choice");
        }

        JsonNode choice = response.path("choices").get(0);
        JsonNode message = choice.path("message");
        if (!message.isObject()) {
            throw new IllegalStateException("千问 API 返回的 message 格式无效");
        }
        return message;
    }

    /** 判断工具调用数组是否非空；缺失或 null 表示无调用，其他类型视为格式错误。 */
    private boolean hasToolCalls(JsonNode toolCalls) {
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

