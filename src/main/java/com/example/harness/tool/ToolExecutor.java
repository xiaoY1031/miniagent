package com.example.harness.tool;

import org.springframework.stereotype.Component;

@Component
public class ToolExecutor {

    private final ProjectInfoTool projectInfoTool;

    public ToolExecutor(ProjectInfoTool projectInfoTool) {
        this.projectInfoTool = projectInfoTool;
    }

    public String execute(String toolName) {

        if ("get_project_info".equals(toolName)) {
            return projectInfoTool.getProjectInfo();
        }

        throw new IllegalArgumentException("不支持的工具：" + toolName);
    }
}
