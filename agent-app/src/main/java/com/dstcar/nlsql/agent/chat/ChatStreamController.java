package com.dstcar.nlsql.agent.chat;

import com.dstcar.nlsql.agent.auth.JwtFilter;
import com.dstcar.nlsql.agent.conversation.ConversationRepository;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.UserMessage;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天流式接口(SSE):POST /api/chat/stream(需 JWT)。
 * AgentScope streamEvents 事件流投影为 5 种前端事件;流正常结束时把本轮
 * user/assistant 全文落 invoice_agent_message(失败不落库,与旧同步接口语义一致)。
 * Agent 上下文由 MysqlAgentStateStore 按 (userId, conversationId) 持久化。
 */
@RestController
@RequestMapping("/api")
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);
    private static final int TITLE_MAX = 30;

    private final ReActAgent agent;
    private final ConversationRepository conversations;

    public ChatStreamController(ReActAgent agent, ConversationRepository conversations) {
        this.agent = agent;
        this.conversations = conversations;
    }

    public record ChatRequest(String conversationId, String message) {
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> stream(@RequestBody ChatRequest request, HttpServletRequest req) {
        String userId = requireUserId(req);
        // 确定会话:无 conversationId 则新建并落库;有则校验归属(防越权访问他人会话)
        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? newConversation(userId, request.message())
                : request.conversationId();
        if (!conversations.owns(conversationId, userId)) {
            log.warn("[chat-stream] 会话不存在或越权 conversationId={} userId={}", conversationId, userId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或不属于当前用户");
        }
        // 首条消息:若标题仍是占位符(如按钮预建的"新会话 …"),用首条消息摘要覆盖
        titleOnFirstMessage(conversationId, request.message());

        log.info("[chat-stream] 收到 userId={} conversationId={} message=\"{}\"",
                userId, conversationId, summarize(request.message()));
        long start = System.nanoTime();
        AtomicReference<Msg> finalMsg = new AtomicReference<>();

        RuntimeContext ctx = RuntimeContext.builder().userId(userId).sessionId(conversationId).build();
        return Flux.concat(
                        Flux.just(sse(ChatStreamEvent.start(conversationId))),
                        agent.streamEvents(List.of(new UserMessage("user", request.message())), ctx)
                                .doOnNext(evt -> {
                                    if (evt instanceof AgentResultEvent r) {
                                        finalMsg.set(r.getResult());
                                    }
                                })
                                // 正常完成才落库(doOnComplete);取消/异常不落,与旧接口语义一致
                                .doOnComplete(() -> {
                                    conversations.appendMessage(conversationId, "user", request.message());
                                    Msg msg = finalMsg.get();
                                    conversations.appendMessage(conversationId, "assistant",
                                            msg == null ? "" : msg.getTextContent());
                                    log.info("[chat-stream] 完成 conversationId={} 耗时={}ms 回答长度={}",
                                            conversationId, (System.nanoTime() - start) / 1_000_000,
                                            msg == null ? 0 : msg.getTextContent().length());
                                })
                                .mapNotNull(ChatStreamController::toEvent)
                                .map(ChatStreamController::sse))
                .concatWith(Mono.fromSupplier(() -> sse(ChatStreamEvent.done())))
                .onErrorResume(e -> {
                    log.error("[chat-stream] 失败 conversationId={} 耗时={}ms 异常={}: {}",
                            conversationId, (System.nanoTime() - start) / 1_000_000,
                            e.getClass().getSimpleName(), e.getMessage());
                    ChatStreamEvent ev = ChatStreamEvent.error("分析失败:" + e.getMessage());
                    return Flux.just(sse(ev));
                });
    }

    /** AgentEvent → 前端事件;思考块/模型调用等事件不透出(返回 null 被 mapNotNull 过滤)。 */
    private static ChatStreamEvent toEvent(AgentEvent evt) {
        if (evt instanceof TextBlockDeltaEvent d) {
            return ChatStreamEvent.delta(d.getDelta());
        }
        if (evt instanceof ToolCallStartEvent t) {
            log.info("[chat-stream] 工具开始 {}", t.getToolCallName());
            return ChatStreamEvent.tool(shortName(t.getToolCallName()), "start");
        }
        if (evt instanceof ToolResultEndEvent t) {
            boolean ok = t.getState() == ToolResultState.SUCCESS;
            log.info("[chat-stream] 工具结束 {} state={}", t.getToolCallName(), t.getState());
            return ChatStreamEvent.tool(shortName(t.getToolCallName()), ok ? "success" : "error");
        }
        return null;
    }

    /** mcp__db-server__list_tables → list_tables(前端展示短名)。 */
    private static String shortName(String tool) {
        int i = tool.lastIndexOf("__");
        return i >= 0 ? tool.substring(i + 2) : tool;
    }

    private static ServerSentEvent<ChatStreamEvent> sse(ChatStreamEvent e) {
        return ServerSentEvent.builder(e).build();
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
