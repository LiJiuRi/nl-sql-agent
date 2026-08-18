package com.dstcar.nlsql.agent.conversation;

import java.time.Instant;

/** 前端历史消息(role=user/assistant)。与 AgentScope AgentState 解耦(见 schema-mysql.sql)。 */
public record MessageEntry(String role, String content, Instant createdAt) {
}
