package com.dstcar.nlsql.agent.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

/** SSE 事件 DTO:AgentScope AgentEvent 的精简前端投影(前端只需这 5 种)。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatStreamEvent(String type, String text, String name, String state, String message,
                              String conversationId) {

    /** 会话已确定(新建会话时前端据此拿到 conversationId)。 */
    static ChatStreamEvent start(String conversationId) {
        return new ChatStreamEvent("start", null, null, null, null, conversationId);
    }

    static ChatStreamEvent delta(String text) {
        return new ChatStreamEvent("delta", text, null, null, null, null);
    }

    static ChatStreamEvent tool(String name, String state) {
        return new ChatStreamEvent("tool", null, name, state, null, null);
    }

    static ChatStreamEvent done() {
        return new ChatStreamEvent("done", null, null, null, null, null);
    }

    static ChatStreamEvent error(String message) {
        return new ChatStreamEvent("error", null, null, null, message, null);
    }
}
