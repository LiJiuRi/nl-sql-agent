package com.dstcar.nlsql.agent.chat;

import com.dstcar.nlsql.agent.auth.JwtFilter;
import com.dstcar.nlsql.agent.conversation.ConversationRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * 聊天接口(非流式 v1):POST /api/chat(需 JWT)。
 * 以 conversationId 串联多轮记忆(持久化),按 userId 隔离会话(ADR-0007)。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final int TITLE_MAX = 30;

    private final ChatClient chatClient;
    private final ConversationRepository conversations;

    public ChatController(ChatClient chatClient, ConversationRepository conversations) {
        this.chatClient = chatClient;
        this.conversations = conversations;
    }

    public record ChatRequest(String conversationId, String message) {
    }

    public record ChatResponse(String conversationId, String content) {
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request, HttpServletRequest req) {
        String userId = requireUserId(req);
        // 确定会话:无 conversationId 则新建并落库;有则校验归属(防越权访问他人会话)
        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? newConversation(userId, request.message())
                : request.conversationId();
        if (!conversations.owns(conversationId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或不属于当前用户");
        }

        // 首条消息:若标题仍是占位符(如按钮预建的"新会话 …"),用首条消息摘要覆盖,使侧栏标题有意义
        titleOnFirstMessage(conversationId, request.message());

        log.info("[chat] 收到 userId={} conversationId={} message=\"{}\"",
                userId, conversationId, summarize(request.message()));
        long start = System.nanoTime();
        try {
            String content = chatClient.prompt()
                    .user(request.message())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();

            // 前端历史持久化(user/assistant);Spring AI 记忆另由 JdbcChatMemoryRepository 管理上下文窗口
            conversations.appendMessage(conversationId, "user", request.message());
            conversations.appendMessage(conversationId, "assistant", content == null ? "" : content);

            log.info("[chat] 完成 userId={} conversationId={} 耗时={}ms 回答长度={}",
                    userId, conversationId, (System.nanoTime() - start) / 1_000_000,
                    content == null ? 0 : content.length());
            return new ChatResponse(conversationId, content);
        } catch (RuntimeException e) {
            log.error("[chat] 失败 conversationId={} 耗时={}ms 异常={}: {}",
                    conversationId, (System.nanoTime() - start) / 1_000_000,
                    e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    private String newConversation(String userId, String firstMessage) {
        String conversationId = UUID.randomUUID().toString();
        String title = summarize(firstMessage);
        if (title.isBlank()) {
            title = "新会话";
        }
        conversations.create(conversationId, userId, title);
        return conversationId;
    }

    /** 首条消息时把占位标题(以"新会话"开头)更新为首条消息摘要,与 newConversation 的标题逻辑保持一致。 */
    private void titleOnFirstMessage(String conversationId, String firstMessage) {
        String current = conversations.titleOf(conversationId);
        if (current != null && current.startsWith("新会话")) {
            String title = summarize(firstMessage);
            if (!title.isBlank()) {
                conversations.rename(conversationId, title);
            }
        }
    }

    private static String requireUserId(HttpServletRequest req) {
        String uid = (String) req.getAttribute(JwtFilter.USER_ID_ATTR);
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return uid;
    }

    /** 文本摘要:截断到 30 字符(用于日志 + 会话标题)。 */
    private static String summarize(String text) {
        if (text == null) return "";
        return text.length() <= TITLE_MAX ? text : text.substring(0, TITLE_MAX) + "...";
    }
}
