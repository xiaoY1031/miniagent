package com.example.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
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

    /**
     * 接收用户问题，循环执行并回传工具结果，返回模型的最终文本回答。
     * 最多执行四轮工具调用，之后再请求一次不使用工具的回答。
     */
    @Override
    public String chat(String question) {
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", question));
        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            JsonNode message = requestChoice(messages, round == MAX_TOOL_ROUNDS ? "none" : "auto")
                    .path("message");
            JsonNode toolCalls = getToolCalls(message);
            if (toolCalls.isEmpty()) {
                //从返回结果里取出回答
                JsonNode content = message.path("content");
                if (!content.isTextual() || content.asText().isBlank()) {
                    throw new IllegalStateException("千问 API 没有返回有效回答");
                }
                return content.asText();
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
                String result = executeToolWithArguments(toolCall, name);
                messages.add(Map.of("role", "tool", "tool_call_id", id, "content", result));
            }
        }
        throw new IllegalStateException("工具调用未完成");
    }

    /**
     * 接收用户问题，只请求模型一次；有工具调用则执行第一个工具并直接返回结果。
     * 没有工具调用则返回模型文本，本方法不会把工具结果再次交给模型。
     */
    public String chatWithTools(String question) {
        JsonNode choice = requestChoice(List.of(Map.of("role", "user", "content", question)), "auto");
        JsonNode message = choice.path("message");
        JsonNode toolCalls = getToolCalls(message);

        // 模型没有请求调用工具
        if (toolCalls.isEmpty()) {
            return message.path("content").asText();
        }

        // 先执行模型请求的第一个工具
        JsonNode toolCall = toolCalls.get(0);
        String toolName = toolCall.path("function").path("name").asText();
        return toolExecutor.execute(toolName);
    }

    /**
     * 将调用方准备好的对话记录发送给模型，返回完整 JSON 响应，不附带工具说明。
     * 由调用方判断 content 和 tool_calls，不在本方法中提取最终答案。
     * 使用工具结果时，messages 应包含用户消息、原始 assistant 工具调用消息，
     * 以及带有对应 tool_call_id 的 tool 结果消息；本方法不自动组装这些记录。
     */
    public JsonNode getFinalAnswer(List<Map<String, Object>> messages) {
        //组装请求体
        Map<String, Object> requestBody = Map.of(
                "model", model,
                //对话记录
                "messages", messages
        );

        //向大模型发送 HTTP 请求，模型根据工具结果组织回答
        JsonNode response = sendRequest(requestBody);

        //返回完整模型响应，供后续 Agent Loop 判断回答或工具调用。
        return response;
    }

    /**
     * 携带对话记录和工具说明请求模型，返回第一条完整 choice。
     * toolChoice 为 auto 时由模型决定是否调用工具，为 none 时禁止工具调用。
     */
    private JsonNode requestChoice(List<Object> messages, String toolChoice) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", messages,
                "tools", toolDefinitions(),
                "tool_choice", toolChoice
        );

        //真正发送 HTTP 请求，模型可能返回普通文本或工具调用；none 时不应提出工具调用。
        return extractChoice(sendRequest(requestBody));
    }

    /** 返回可用工具的 JSON Schema 描述；当前仅支持无参数的 get_project_info。 */
    private List<Map<String, Object>> toolDefinitions() {
        //工具描述
        return List.of(Map.of(
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
                ));
    }

    /**
     * 使用密钥向千问发送请求，返回完整 JSON 响应，不解析 choices 或回答正文。
     * HTTP 请求失败时由 RestClient 抛出异常；响应结构由解析方法检查。
     */
    private JsonNode sendRequest(Map<String, Object> requestBody) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("请配置 DASHSCOPE_API_KEY");
        }
        return restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

    }

    /** 检查完整响应是否包含 choice 和 message，返回第一条 choice；不要求包含回答正文。 */
    private JsonNode extractChoice(JsonNode response) {
        //检查响应是否包含 choice 和 message；这里不检查回答文本是否为空。
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

    /** 检查完整响应并提取第一条回答正文；缺失、非文本或空白正文均视为无效回答。 */
    private String extractAnswer(JsonNode response) {
        JsonNode content = extractChoice(response).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new IllegalStateException("千问 API 没有返回有效回答正文");
        }
        return content.asText();
    }

    /** 从 assistant 消息提取工具调用数组；缺失或 null 时返回空数组，类型错误时抛出异常。 */
    private JsonNode getToolCalls(JsonNode message) {
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isMissingNode() || toolCalls.isNull()) {
            return objectMapper.createArrayNode();
        }
        if (!toolCalls.isArray()) {
            throw new IllegalStateException("千问返回的 tool_calls 格式无效");
        }
        return toolCalls;
    }

    /**
     * 为多轮 chat 流程检查工具名称和参数，再交给执行器。
     * 当前工具要求 arguments 是空的 JSON 对象；不支持的名称或无效参数返回工具错误文本。
     */
    private String executeToolWithArguments(JsonNode toolCall, String name) {
        if (!"get_project_info".equals(name)) {
            return "工具错误：不支持的工具 " + name;
        }
        try {
            JsonNode arguments = objectMapper.readTree(
                    toolCall.path("function").path("arguments").asText());
            if (arguments == null || !arguments.isObject() || !arguments.isEmpty()) {
                return "工具错误：get_project_info 的参数必须是空对象 {}";
            }
            return toolExecutor.execute(name);
        } catch (JsonProcessingException e) {
            return "工具错误：参数不是有效 JSON";
        }
    }
}
