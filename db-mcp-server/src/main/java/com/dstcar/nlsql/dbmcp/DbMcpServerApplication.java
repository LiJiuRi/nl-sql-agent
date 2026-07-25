package com.dstcar.nlsql.dbmcp;

import com.dstcar.nlsql.dbmcp.config.DbProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 自建只读 SQLite MCP 服务端:暴露 4 个工具给 agent-app 调用(stdio 传输)。 */
@SpringBootApplication
@EnableConfigurationProperties(DbProperties.class)
public class DbMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbMcpServerApplication.class, args);
    }
}
