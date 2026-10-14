package com.example.harness.agent;

import com.example.harness.llm.QwenService;
import com.example.harness.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentLoopTest {

    /** 模拟有工具调用、空数组、null 和缺失字段，验证分支判断；不调用真实 API。 */
    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "missing", "[{\"function\":{\"name\":\"get_project_info\"}}]"})
    void identifiesToolCalls(String toolCalls) throws Exception {
        QwenService service = mock(QwenService.class);
        String field = "missing".equals(toolCalls) ? "" : ",\"tool_calls\":" + toolCalls;
        var response = new ObjectMapper().readTree(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"你好\"" + field + "}}]}");
        when(service.getFinalAnswer(List.of())).thenReturn(response);

        assertEquals(toolCalls.startsWith("[{"), new AgentLoop(service, mock(ToolExecutor.class),
                new ObjectMapper()).needsToolCall(List.of()));
    }

    /** 验证两轮工具调用后的消息历史完整，并在第三轮直接回答时结束。 */
    @Test
    void continuesUntilModelAnswers() throws Exception {
        QwenService service = mock(QwenService.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        ObjectMapper mapper = new ObjectMapper();
        var toolResponse = mapper.readTree("""
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{
                "id":"call_1","type":"function","function":{"name":"get_project_info","arguments":"{}"}}]}}]}
                """);
        when(executor.execute("get_project_info")).thenReturn("CampusX");
        when(service.getFinalAnswer(anyList())).thenAnswer(invocation -> {
            List<java.util.Map<String, Object>> history = invocation.getArgument(0);
            if (history.size() == 1 || history.size() == 3) {
                return toolResponse;
            }
            assertEquals(5, history.size());
            assertEquals("assistant", history.get(1).get("role"));
            assertEquals("call_1", history.get(2).get("tool_call_id"));
            assertEquals("CampusX", history.get(4).get("content"));
            return mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"最终回答\"}}]}");
        });
        assertEquals("最终回答", new AgentLoop(service, executor, mapper).run("项目介绍"));
        verify(executor, times(2)).execute("get_project_info");
        verify(service, times(3)).getFinalAnswer(anyList());
    }

    /** 验证模型一直请求工具时，在上限处结束并报错，不无限执行。 */
    @Test
    void stopsRepeatedToolCallsAtLimit() throws Exception {
        QwenService service = mock(QwenService.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        ObjectMapper mapper = new ObjectMapper();
        when(service.getFinalAnswer(anyList())).thenReturn(mapper.readTree("""
                {"choices":[{"message":{"role":"assistant","tool_calls":[{
                "id":"call_1","type":"function","function":{"name":"get_project_info","arguments":"{}"}}]}}]}
                """));
        when(executor.execute("get_project_info")).thenReturn("CampusX");
        assertThrows(IllegalStateException.class, () -> new AgentLoop(service, executor, mapper).run("介绍"));
        verify(executor, times(4)).execute("get_project_info");
    }
}
