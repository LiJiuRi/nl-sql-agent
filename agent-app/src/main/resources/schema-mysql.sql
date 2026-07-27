-- 记忆库 schema(存中心 MySQL 的 agent_memory 库,ADR-0007)
-- 由 spring.ai.chat.memory.repository.jdbc.schema 在启动时执行(幂等)。

-- Spring AI 会话记忆(由 spring-ai-starter-model-chat-memory-repository-jdbc 管理)
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36)  NOT NULL,
    content         TEXT         NOT NULL,
    type            VARCHAR(10)  NOT NULL,
    `timestamp`     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_sacm_conv_ts (conversation_id, `timestamp`)
);

-- 多会话元数据(ADR-0007:按 userId 隔离,各自私有)
CREATE TABLE IF NOT EXISTS conversation (
    conversation_id VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_conv_user (user_id)
);

-- 前端历史(读模型,与 Spring AI 记忆解耦:Spring AI 记忆管 LLM 上下文窗口,本表管全量历史展示)
CREATE TABLE IF NOT EXISTS message (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(36)  NOT NULL,
    role            VARCHAR(10)  NOT NULL,
    content         TEXT         NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_msg_conv (conversation_id)
);
