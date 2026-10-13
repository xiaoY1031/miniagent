package com.example.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.harness.tool.ToolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@Service
public class QwenService implements LlmClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;
    private static final int MAX_TOOL_ROUNDS = 4;

    //QwenService 的构造方法，负责初始化服务。Spring 启动时会自动提供这些参数并创建对象，不是每次用户提问都执行。
    public QwenService(
            @Value("${qwen.api-key}") String apiKey,
            @Value("${qwen.base-url}") String baseUrl,
            @Value("${qwen.model}") String model,
            ToolExecutor toolExecutor,
            ObjectMapper objectMapper,
            RestClient.Builder builder) {

        this.apiKey = apiKey;
        this.model = model;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
        this.restClient = builder
                .baseUrl(baseUrl)
                .build();
    }

    @Override
    public String chat(String question) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("请配置 DASHSCOPE_API_KEY");
        }

        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", question));
        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            JsonNode message = request(messages, round == MAX_TOOL_ROUNDS ? "none" : "auto");
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isMissingNode() || toolCalls.isNull()
                    || (toolCalls.isArray() && toolCalls.isEmpty())) {
                //从返回结果里取出回答
                JsonNode content = message.path("content");
                if (!content.isTextual() || content.asText().isBlank()) {
                    throw new IllegalStateException("千问 API 没有返回有效回答");
                }
                return content.asText();
            }
            if (!toolCalls.isArray()) {
                throw new IllegalStateException("千问返回的 tool_calls 格式无效");
            }
            if (round == MAX_TOOL_ROUNDS) {
                throw new IllegalStateException("工具调用超过最大轮数");
            }

            // 保留模型的完整工具调用消息，再逐个追加对应的执行结果。
            messages.add(message);
            for (JsonNode toolCall : toolCalls) {
                String id = toolCall.path("id").asText();
                String name = toolCall.path("function").path("name").asText();
                if (id.isBlank() || !"function".equals(toolCall.path("type").asText())) {
                    throw new IllegalStateException("千问返回的工具调用缺少 ID 或类型无效");
                }
                String result;
                if (!"get_project_info".equals(name)) {
                    result = "工具错误：不支持的工具 " + name;
                } else {
                    try {
                        JsonNode arguments = objectMapper.readTree(
                                toolCall.path("function").path("arguments").asText());
                        if (arguments == null || !arguments.isObject() || !arguments.isEmpty()) {
                            result = "工具错误：get_project_info 的参数必须是空对象 {}";
                        } else {
                            result = toolExecutor.execute(name);
                        }
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        result = "工具错误：参数不是有效 JSON";
                    }
                }
                messages.add(Map.of("role", "tool", "tool_call_id", id, "content", result));
            }
        }
        throw new IllegalStateException("工具调用未完成");
    }

    public String chatWithTools(String question) {
        JsonNode choice = requestChoice(List.of(Map.of("role", "user", "content", question)), "auto");
        JsonNode message = choice.path("message");
        JsonNode toolCalls = message.path("tool_calls");

        // 模型没有请求调用工具
        if (toolCalls.isMissingNode() || toolCalls.isNull() || toolCalls.isEmpty()) {
            return message.path("content").asText();
        }
        if (!toolCalls.isArray()) {
            throw new IllegalStateException("千问返回的 tool_calls 格式无效");
        }

        // 先执行模型请求的第一个工具
        JsonNode toolCall = toolCalls.get(0);
        String toolName = toolCall.path("function").path("name").asText();
        return toolExecutor.execute(toolName);
    }

    public String getFinalAnswer(List<Map<String, Object>> messages) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("请配置 DASHSCOPE_API_KEY");
        }

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", messages
        );

        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.path("choices").isArray()
                || response.path("choices").isEmpty()) {
            throw new IllegalStateException("千问 API 没有返回有效回答");
        }

        return response.path("choices")
                .get(0)
                .path("message")
                .path("content")
                .asText();
    }

    private JsonNode request(List<Object> messages, String toolChoice) {
        return requestChoice(messages, toolChoice).path("message");
    }

    private JsonNode requestChoice(List<Object> messages, String toolChoice) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("请配置 DASHSCOPE_API_KEY");
        }
        //工具描述
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", messages,
                "tools", List.of(Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "get_project_info",
                                "description", "查询 CampusX 项目的基本信息和技术栈",
                                //调用这个工具时，需要传入什么参数。
                                "parameters", Map.of(
                                        "type", "object",//参数整体是一个对象
                                        "properties", Map.of(),//这个对象有哪些参数
                                        "required", List.of(),//哪些参数必填
                                        "additionalProperties", false)//是否允许额外的参数
                        )
                )),
                "tool_choice", toolChoice
        );

        //真正发送 HTTP 请求
        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.path("choices").isArray()
                || response.path("choices").isEmpty()) {
            throw new IllegalStateException(
                    "千问 API 没有返回有效回答");
        }

        JsonNode message = response.path("choices")
                .get(0)
                .path("message");
        if (!message.isObject()) {
            throw new IllegalStateException("千问 API 返回的 message 格式无效");
        }
        return response.path("choices").get(0);
    }
}
