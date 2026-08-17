# agent-app 框架从 Spring AI 2.0 重写为 AgentScope Java 2.0;聊天接口改 SSE 流式

需求:页面发起的分析结果改 SSE 流式输出(文本增量 + 工具调用状态)。Spring AI 的 ChatClient 流式 + MCP + JDBC 记忆三件套拼装成本高,而 AgentScope Java 2.0.0 GA(2026-07-10)原生提供类型化事件流(`streamEvents` → `Flux<AgentEvent>`)、无状态多会话 agent、内置 MCP stdio 客户端与 MySQL 状态存储,恰好覆盖全部诉求。

决定:agent-app 重写为 AgentScope 2.0,**手动装配**(agentscope-core + extensions-model-openai + extensions-mysql,自建 `ReActAgent` @Bean),不用官方 spring-boot-starter——starter 面向 Boot 3.x 且本项目用 Boot 4.1,core 是纯 Reactor 库零兼容面;GLM 走 `OpenAIChatModel` builder(自定义 baseUrl + GLM formatter),显式可控。LLM 上下文记忆从"Spring AI JDBC chat memory(10 条窗口)"改为 `MysqlAgentStateStore`(同库 `dst_db_invoice`,新表 `invoice_agent_agent_state` 自动建);`invoice_agent_chat_memory` 表废弃。前端历史(`invoice_agent_message`)、会话元数据、JWT 认证、db-mcp-server 均不变。完整设计见 `docs/superpowers/specs/2026-08-17-agentscope-2.0-rewrite-design.md`。

**Considered Options**:
- **官方 starter 装配**:排除。Boot 4.1 兼容未验证;GLM 自定义端点(Coding Plan)在 starter yaml 中支持不明。
- **保留 Spring AI 只加流式**:排除。Spring AI 也能流式(MVC Flux),但与"用 AgentScope 2.0 正式版重写"的需求相悖。

**Consequences**:聊天端点 `POST /api/chat` → `POST /api/chat/stream`(SSE);MCP 工具名变为 `mcp__db-server__*`(系统提示词同步更新);上下文记忆不再限 10 条窗口(AgentState 全量,超长会话压缩留作后续);前端发消息改 fetch 流式解析。GLM 思考块事件不转发,与现状一致。

**Status**: accepted。
