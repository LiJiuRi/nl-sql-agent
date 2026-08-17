-- 记忆库 schema(存中心 MySQL 的 dst_db_invoice 库,ADR-0007)
-- 由 spring.sql.init(mode=always)在启动时执行(幂等)。
-- 注意:LLM 上下文记忆已改由 AgentScope MysqlAgentStateStore 管理
-- (表 invoice_agent_agent_state,自建,ADR-0008);旧 invoice_agent_chat_memory 废弃不再创建。

-- 多会话元数据(ADR-0007:按 userId 隔离,各自私有)
CREATE TABLE IF NOT EXISTS invoice_agent_conversation (
    conversation_id VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_conv_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 前端历史(读模型,与 AgentScope AgentState 解耦:AgentState 管 LLM 上下文,本表管全量历史展示)
CREATE TABLE IF NOT EXISTS invoice_agent_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(36)  NOT NULL,
    role            VARCHAR(10)  NOT NULL,
    content         TEXT         NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_msg_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
