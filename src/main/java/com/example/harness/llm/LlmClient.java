package com.example.harness.llm;

public interface LlmClient {
    /** 接收用户问题，返回模型的回答文本；具体请求和工具调用流程由实现类负责。 */
    String chat(String question);
}

