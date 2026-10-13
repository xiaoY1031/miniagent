package com.example.harness.llm;

import com.example.harness.tool.ProjectInfoTool;
import com.example.harness.tool.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QwenServiceTest {
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
