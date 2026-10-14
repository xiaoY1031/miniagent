package com.example.harness.tool;

import org.springframework.stereotype.Component;

@Component
public class ToolExecutor {

    private final ProjectInfoTool projectInfoTool;

    /** 由 Spring 注入项目信息工具，保存供执行时调用。 */
    public ToolExecutor(ProjectInfoTool projectInfoTool) {
        this.projectInfoTool = projectInfoTool;
    }

    /** 按模型返回的工具名称调用对应 Java 方法并返回结果；未知名称抛出异常。 */
    public String execute(String toolName) {

        if ("get_project_info".equals(toolName)) {
            return projectInfoTool.getProjectInfo();
        }

        throw new IllegalArgumentException("不支持的工具：" + toolName);
    }
}
