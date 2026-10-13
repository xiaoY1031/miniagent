package com.example.harness.tool;

import org.springframework.stereotype.Component;

@Component
public class ProjectInfoTool {

    public String getProjectInfo() {
        return """
                项目名称：CampusX
                技术栈：Spring Boot、MySQL、Redis
                项目类型：校园生活服务平台
                """;
    }
}
