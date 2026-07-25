package com.dstcar.nlsql.agent.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 聊天接口(非流式 v1):POST /api/chat。
 * 以 conversationId 串联多轮记忆。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public record ChatRequest(String conversationId, String message) {
    }

    public record ChatResponse(String conversationId, String content) {
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? UUID.randomUUID().toString()
                : request.conversationId();

        log.info("[chat] 收到分析需求 conversationId={} message=\"{}\"", conversationId, summarize(request.message()));
        long start = System.nanoTime();
        try {
            String content = chatClient.prompt()
                    .user(request.message())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();

            log.info("[chat] 完成 conversationId={} 耗时={}ms 回答长度={}",
                    conversationId, (System.nanoTime() - start) / 1_000_000,
                    content == null ? 0 : content.length());
            return new ChatResponse(conversationId, content);
        } catch (RuntimeException e) {
            // 对外仍返回 500(行为不变);此处仅补关联日志,便于定位卡在哪一步。
            log.error("[chat] 失败 conversationId={} 耗时={}ms 异常={}: {}",
                    conversationId, (System.nanoTime() - start) / 1_000_000,
                    e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    /** 长文本摘要:截断到 200 字符,避免日志噪声。 */
    private static String summarize(String text) {
        if (text == null) return "";
        return text.length() <= 200 ? text : text.substring(0, 200) + "...(" + text.length() + "字)";
    }
}
