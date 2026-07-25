package com.dstcar.nlsql.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Agent 主程序:Spring Boot Web + GLM + 工具调用循环 + MCP 客户端 + 聊天界面。 */
@SpringBootApplication
public class AgentAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentAppApplication.class, args);
    }
}
