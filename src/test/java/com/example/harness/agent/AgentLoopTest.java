package com.example.harness.agent;

import com.example.harness.llm.QwenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertEquals(toolCalls.startsWith("[{"), new AgentLoop(service).needsToolCall(List.of()));
    }
}
