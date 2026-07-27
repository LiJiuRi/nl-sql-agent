package com.dstcar.nlsql.agent.conversation;

import java.time.Instant;

/** 会话元数据(ADR-0007:按 userId 隔离)。 */
public record Conversation(String conversationId, String title, Instant createdAt) {
}
