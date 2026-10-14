package com.example.harness.tool;

import org.springframework.stereotype.Component;

@Component
public class ProjectInfoTool {

    /** 返回代码中固定的 CampusX 项目信息，不读取数据库或项目文件。 */
    public String getProjectInfo() {
        return """
                项目名称：CampusX
                技术栈：Spring Boot、MySQL、Redis
                项目类型：校园生活服务平台
                """;
    }
}
