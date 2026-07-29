package com.dstcar.nlsql.agent.memory;

import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepositoryDialect;

/**
 * 自定义 chat memory dialect:把 Spring AI 默认表名 SPRING_AI_CHAT_MEMORY 改成
 * invoice_agent_chat_memory(统一 invoice_agent_ 前缀)。
 *
 * Spring AI 2.0.0 表名写死在 dialect 的 4 条 SQL 里(接口 default + MysqlChatMemoryRepositoryDialect override),
 * 无配置项可改,故此处 4 个方法全部 override。字段对齐 MysqlChatMemoryRepositoryDialect(timestamp 加反引号)。
 */
public class InvoiceAgentMysqlChatMemoryRepositoryDialect implements JdbcChatMemoryRepositoryDialect {

    private static final String TABLE = "invoice_agent_chat_memory";

    @Override
    public String getSelectMessagesSql() {
        return "SELECT content, type, `timestamp` FROM " + TABLE
                + " WHERE conversation_id = ? ORDER BY sequence_id";
    }

    @Override
    public String getInsertMessageSql() {
        return "INSERT INTO " + TABLE
                + " (conversation_id, content, type, `timestamp`, sequence_id) VALUES (?, ?, ?, ?, ?)";
    }

    @Override
    public String getSelectConversationIdsSql() {
        return "SELECT DISTINCT conversation_id FROM " + TABLE;
    }

    @Override
    public String getDeleteMessagesSql() {
        return "DELETE FROM " + TABLE + " WHERE conversation_id = ?";
    }
}
