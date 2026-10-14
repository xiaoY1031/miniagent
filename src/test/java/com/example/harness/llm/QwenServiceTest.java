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
    /** 验证最终回答请求发送完整消息历史、不携带工具说明，并从完整响应提取正文。 */
    @Test
    void extractsFinalAnswerFromResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenService service = new QwenService("test-key", "https://example.test/v1",
                "qwen-plus", new ToolExecutor(new ProjectInfoTool()), new ObjectMapper(), builder);
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andExpect(jsonPath("$.messages[0].tool_call_id").value("call_1"))
                .andRespond(withSuccess("""
                        {"id":"response_1","choices":[{"message":{"role":"assistant","content":"CampusX 使用 Redis。"}}]}
                        """, MediaType.APPLICATION_JSON));
        assertEquals("CampusX 使用 Redis。", service.getFinalAnswer(java.util.List.of(
                java.util.Map.of("role", "tool", "tool_call_id", "call_1", "content", "Redis"))));
        server.verify();
    }

    /** 验证缺少回答正文时明确报错，不把 null 当作有效文本返回。 */
    @Test
    void rejectsFinalResponseWithoutAnswer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QwenService service = new QwenService("test-key", "https://example.test/v1",
                "qwen-plus", new ToolExecutor(new ProjectInfoTool()), new ObjectMapper(), builder);
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":null}}]}
                        """, MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, () -> service.getFinalAnswer(java.util.List.of()));
        server.verify();
    }

    /** 模拟模型提出工具调用，验证只执行第一个工具并直接返回结果。 */
    @Test
    void executesFirstToolCallAndReturnsItsResult() {
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
        assertEquals("CampusX 项目信息", service.chatWithTools("查询 CampusX 的技术栈"));
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
