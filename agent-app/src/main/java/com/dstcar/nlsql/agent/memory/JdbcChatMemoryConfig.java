package com.dstcar.nlsql.agent.memory;

import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 显式声明 JdbcChatMemoryRepository,用自定义 dialect 固定表名为 invoice_agent_chat_memory。
 *
 * 必须自己声明这个 bean:auto-config 的 dialect 是 JdbcChatMemoryRepositoryDialect.from(dataSource) 现造的
 * (MysqlChatMemoryRepositoryDialect,表名仍是默认的 SPRING_AI_CHAT_MEMORY),不会取容器里的 dialect bean。
 * 本 bean 因 @ConditionalOnMissingBean 让 auto-config 的同名 bean 退让;不要排除 auto-config ——
 * 建表脚本执行器(JdbcChatMemoryRepositorySchemaInitializer)仍由它提供。
 */
@Configuration
public class JdbcChatMemoryConfig {

    @Bean
    JdbcChatMemoryRepository jdbcChatMemoryRepository(JdbcTemplate jdbcTemplate) {
        return JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .dialect(new InvoiceAgentMysqlChatMemoryRepositoryDialect())
                .build();
    }
}
