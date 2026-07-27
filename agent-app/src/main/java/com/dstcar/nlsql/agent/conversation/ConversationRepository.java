package com.dstcar.nlsql.agent.conversation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 会话元数据 + 前端历史的持久化(中心 MySQL 的 agent_memory 库,ADR-0007)。 */
@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Conversation> listByUser(String userId) {
        return jdbc.query(
                "SELECT conversation_id, title, created_at FROM conversation "
                        + "WHERE user_id = ? ORDER BY created_at DESC",
                (rs, i) -> new Conversation(
                        rs.getString("conversation_id"),
                        rs.getString("title"),
                        toInstant(rs.getTimestamp("created_at"))),
                userId);
    }

    public void create(String conversationId, String userId, String title) {
        jdbc.update("INSERT INTO conversation(conversation_id, user_id, title) VALUES (?,?,?)",
                conversationId, userId, title);
    }

    public boolean owns(String conversationId, String userId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE conversation_id = ? AND user_id = ?",
                Integer.class, conversationId, userId);
        return c != null && c > 0;
    }

    public String titleOf(String conversationId) {
        return jdbc.query("SELECT title FROM conversation WHERE conversation_id = ?",
                rs -> rs.next() ? rs.getString("title") : null, conversationId);
    }

    public void rename(String conversationId, String title) {
        jdbc.update("UPDATE conversation SET title = ? WHERE conversation_id = ?", title, conversationId);
    }

    public void appendMessage(String conversationId, String role, String content) {
        jdbc.update("INSERT INTO message(conversation_id, role, content) VALUES (?,?,?)",
                conversationId, role, content);
    }

    public List<MessageEntry> messages(String conversationId) {
        return jdbc.query(
                "SELECT role, content, created_at FROM message WHERE conversation_id = ? ORDER BY id",
                (rs, i) -> new MessageEntry(
                        rs.getString("role"),
                        rs.getString("content"),
                        toInstant(rs.getTimestamp("created_at"))),
                conversationId);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
