package com.dstcar.nlsql.dbmcp.config;

import com.dstcar.nlsql.dbmcp.tools.DbTools;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式把 DbTools 的 @Tool 方法注册为 ToolCallbackProvider。
 * ToolCallbackConverterAutoConfiguration 会把它转成 MCP SyncToolSpecification,
 * 由 MCP 服务端暴露。
 * (注解扫描器在 2.0 未能稳定识别 @Tool,改用显式注册,可靠且直白。)
 */
@Configuration
public class ToolConfig {

    @Bean
    ToolCallbackProvider dbToolCallbackProvider(DbTools dbTools) {
        ToolCallback[] callbacks = ToolCallbacks.from(dbTools);
        return ToolCallbackProvider.from(callbacks);
    }
}
