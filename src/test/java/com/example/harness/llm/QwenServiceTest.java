package com.example.harness.llm;

import com.example.harness.tool.ProjectInfoTool;
import com.example.harness.tool.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QwenServiceTest {
    /** 验证模型持续请求工具时恰好请求十轮，并抛出明确的循环上限异常。 */
    @Test
    void stopsAgentAfterTenModelRequests() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenService service = new QwenService("test-key", "https://example.test/v1",
                "qwen-plus", new ToolExecutor(new ProjectInfoTool()), new ObjectMapper(), builder);
        for (int round = 0; round < 10; round++) {
            server.expect(requestTo("https://example.test/v1/chat/completions"))
                    .andExpect(jsonPath("$.tools[0].function.name").value("get_project_info"))
                    .andExpect(jsonPath("$.tool_choice").value("auto"))
                    .andRespond(withSuccess("""
                            {"choices":[{"message":{"role":"assistant","content":null,
                            "tool_calls":[{"id":"call_1","type":"function","function":{
                            "name":"get_project_info","arguments":"{}"}}]}}]}
                            """, MediaType.APPLICATION_JSON));
        }
        var error = assertThrows(IllegalStateException.class, () -> service.runAgent("介绍项目"));
        assertEquals("Agent 超过最大循环次数 10 轮", error.getMessage());
        server.verify();
    }

    /** 验证请求携带工具说明，返回包含响应 ID 和 choices 的完整 JSON。 */
    @Test
    void returnsFullResponseForFinalAnswer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenService service = new QwenService("test-key", "https://example.test/v1",
                "qwen-plus", new ToolExecutor(new ProjectInfoTool()), new ObjectMapper(), builder);
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.tools[0].function.name").value("get_project_info"))
                .andExpect(jsonPath("$.messages[0].tool_call_id").value("call_1"))
                .andRespond(withSuccess("""
                        {"id":"response_1","choices":[{"message":{"role":"assistant","content":"CampusX 使用 Redis。"}}]}
                        """, MediaType.APPLICATION_JSON));
        var response = service.getFinalAnswer(java.util.List.of(
                java.util.Map.of("role", "tool", "tool_call_id", "call_1", "content", "Redis")));
        assertEquals("response_1", response.path("id").asText());
        assertEquals("CampusX 使用 Redis。", response.path("choices").get(0)
                .path("message").path("content").asText());
        server.verify();
    }

    /** 验证正文为 null 时仍保留工具调用信息，供后续循环解析，不执行工具。 */
    @Test
    void preservesToolCallsInFullResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenService service = new QwenService("test-key", "https://example.test/v1",
                "qwen-plus", new ToolExecutor(new ProjectInfoTool()), new ObjectMapper(), builder);
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call_1","type":"function","function":{
                        "name":"get_project_info","arguments":"{}"}}]}}]}
                        """, MediaType.APPLICATION_JSON));
        var response = service.getFinalAnswer(java.util.List.of());
        assertEquals("get_project_info", response.path("choices").get(0).path("message")
                .path("tool_calls").get(0).path("function").path("name").asText());
        server.verify();
    }

    /** 模拟工具调用及最终回答，验证保留 assistant 消息并携带匹配 ID 回传工具结果。 */
    @Test
    void executesToolAndReturnsModelFinalAnswer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ToolExecutor tool = org.mockito.Mockito.mock(ToolExecutor.class);
        org.mockito.Mockito.when(tool.execute("get_project_info")).thenReturn("CampusX 项目信息");
        QwenService service = new QwenService("test-key", "https://example.test/v1",
                "qwen-plus", tool, new ObjectMapper(), builder);
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andExpect(jsonPath("$.tools[0].function.parameters.additionalProperties").value(false))
                .andRespond(withSuccess("""
                        {"choices":[{"index":0,"finish_reason":"tool_calls",
                        "message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call_1","type":"function","function":{
                        "name":"get_project_info","arguments":"{}"}}]}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[0].content").value("查询 CampusX 的技术栈"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].id").value("call_1"))
                .andExpect(jsonPath("$.messages[2].role").value("tool"))
                .andExpect(jsonPath("$.messages[2].tool_call_id").value("call_1"))
                .andExpect(jsonPath("$.messages[2].content").value("CampusX 项目信息"))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"这是模型整理后的项目信息。"}}]}
                        """, MediaType.APPLICATION_JSON));
        assertEquals("这是模型整理后的项目信息。", service.chatWithTools("查询 CampusX 的技术栈"));
        org.mockito.Mockito.verify(tool).execute("get_project_info");
        org.mockito.Mockito.verifyNoMoreInteractions(tool);
        server.verify();
    }

    @Test
    /** 模拟两次模型响应，验证多轮 chat 回传工具结果时保留对应的 tool_call_id。 */
    void executesToolAndSendsResultBackWithMatchingId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenService service = new QwenService("test-key", "https://example.test/v1",
                "qwen-plus", new ToolExecutor(new ProjectInfoTool()), new ObjectMapper(), builder);

        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.tools[0].function.name").value("get_project_info"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call_1","type":"function","function":{
                        "name":"get_project_info","arguments":"{}"}}]}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].id").value("call_1"))
                .andExpect(jsonPath("$.messages[2].role").value("tool"))
                .andExpect(jsonPath("$.messages[2].tool_call_id").value("call_1"))
                .andExpect(jsonPath("$.messages[2].content").value(new ProjectInfoTool().getProjectInfo()))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"CampusX 使用 Spring Boot、MySQL、Redis。"}}]}
                        """, MediaType.APPLICATION_JSON));
        assertEquals("CampusX 使用 Spring Boot、MySQL、Redis。", service.chat("项目用了哪些技术？"));
        server.verify();
    }

    @Test
    /** 模拟普通文本响应，验证 chatWithTools 无工具调用时直接返回回答。 */
    void returnsOrdinaryAnswerWithoutCallingTool() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenService service = new QwenService("test-key", "https://example.test/v1",
                "qwen-plus", new ToolExecutor(new ProjectInfoTool()), new ObjectMapper(), builder);
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"你好"}}]}
                        """, MediaType.APPLICATION_JSON));
        assertEquals("你好", service.chatWithTools("你好"));
        server.verify();
    }
}
