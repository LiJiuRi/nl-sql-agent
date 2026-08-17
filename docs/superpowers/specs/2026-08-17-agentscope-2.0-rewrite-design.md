# AgentScope Java 2.0 重写 + SSE 流式输出 设计文档

日期:2026-08-17 · 分支:`feat/agentscope-sse`(自 `feat/mysql-multi-session` 拉出) · 状态:已确认

## 需求

1. agent-app 框架从 Spring AI 2.0 重写为 **AgentScope Java 2.0.0 GA**(2026-07-10 发布)。
2. 页面发起的分析结果改为 **SSE 流式输出**(文本增量 + 工具调用状态)。
3. 范围仅 agent-app 模块;`db-mcp-server` 是独立进程走 MCP stdio 协议,不动。

## 关键事实(官方文档佐证)

| 事实 | 来源 |
|---|---|
| `agentscope-extensions-model-openai` 提供 `glm:<model>` 一等支持,内置 GLM formatter、默认智谱端点、可自定义 baseUrl;env 读 `ZHIPUAI_API_KEY`/`GLM_API_KEY`/`ZAI_API_KEY` | docs/v2/zh/integration/model/glm.html |
| `ReActAgent` 完全无状态,单例并发服务多 `(userId, sessionId)`;同 session 串行、跨 session 并行 | docs/v2/zh/docs/building-blocks/agent.html |
| `streamEvents()` 返回 `Flux<AgentEvent>`(28 种类型化事件);官方给出 Spring SSE 消费模式;`streamEvents()` 无 RuntimeContext 重载,需 context 时用 `stream(msgs, options, ctx)`(确切签名以 jar 为准) | docs/v2/zh/docs/building-blocks/message-and-event.html |
| `McpClientBuilder.stdio().command().args()` 内置 stdio MCP 客户端;工具命名 `mcp__{server}__{tool}` | docs/v2/zh/docs/building-blocks/tool.html |
| `MysqlAgentStateStore(ds, databaseName, tableName, createIfNotExist)` 按 `(userId, sessionId)` 分桶持久化(打包为 `{userId}:{sessionId}`),自动建表 | docs/v2/zh/integration/session/mysql.html |

## 方案决策

**手动装配,不用官方 starter**。依赖 `agentscope-core` + `agentscope-extensions-model-openai` + `agentscope-extensions-mysql`,自建 `@Bean ReActAgent`。

理由:① starter 面向 Spring Boot 3.x,与本项目 Boot 4.1 的兼容未验证,core 是纯 Reactor 库无 Spring 依赖、零兼容面;② GLM 需自定义 baseUrl(Coding Plan 端点)+ GLM formatter,显式 `OpenAIChatModel.builder()` 全可控;③ 官方文档明确"高级用户始终可以自定义 Model bean"。

## 组件替换表

| 现在(Spring AI) | 重写后(AgentScope 2.0) |
|---|---|
| `ChatClient` + advisor 链(`ChatClientConfig`) | `ReActAgent` 单例 @Bean(系统提示词原文迁移,工具名引用更新为 `mcp__db-server__*`) |
| `spring-ai-starter-mcp-client`(stdio) | `McpClientBuilder.stdio()` 指向 db-mcp-server jar(命令 `${java.home}/bin/java -jar …`,OS env 继承给子进程) |
| JDBC chat memory(`memory/` 包,10 条窗口,`invoice_agent_chat_memory` 表) | `MysqlAgentStateStore`,库 `dst_db_invoice`、表 `invoice_agent_agent_state`(自动建表);旧表废弃不再建 |
| `POST /api/chat`(同步 JSON) | `POST /api/chat/stream`(SSE);旧端点删除 |

**不变**:`db-mcp-server`、`auth/`(JWT)、`conversation/`(元数据 + 前端历史)、`invoice_agent_conversation`/`invoice_agent_message` 两表、4 段回答格式与反幻觉提示词约束。

## SSE 设计

**后端**:Spring MVC 返回 `Flux<ServerSentEvent<ChatStreamEvent>>`(MVC 原生支持 Reactor 类型,不切 WebFlux)。AgentEvent → 精简 DTO:

```
{type:"delta",  text:"…"}                                  ← TextBlockDeltaEvent
{type:"tool",   name:"list_tables", state:"start"|"success"|"error"} ← ToolCallStart / ToolResultEnd(state 映射)
{type:"done"}                                               ← AgentEndEvent
{type:"error",  message:"…"}                                ← 异常
```

- ThinkingBlock 事件不转发(与现状一致)。
- `conversationId` 逻辑复用:无则建会话、首条消息改占位标题、越权校验 404。
- 流正常结束时把 user/assistant 全文落 `invoice_agent_message`(失败不落库,与现有语义一致)。
- RuntimeContext:`userId` = 登录用户、`sessionId` = conversationId。

**前端**:`fetch` POST + ReadableStream 手动解析 SSE 帧(`EventSource` 不支持 POST 和 Authorization 头)。交互:typing 指示器 → 工具状态行 → 文本增量实时追加,完成后 `renderMarkdown` 重排,`loadConversations()` 刷新侧栏。

## 风险与实现期验证点

1. **带 ctx 的流式 API**:`stream(msgs, options, ctx)` 签名以 jar 实际 API 核对后再写控制器。
2. **MCP 子进程环境变量**:ProcessBuilder 默认继承父进程 OS env(README 要求 MYSQL_BILL_* 以 OS env 设置),启动后验证连通。
3. **上下文无窗口上限**:AgentState 保存全量上下文(旧方案 10 条窗口)。GLM 5.2 窗口足够,先不做截断;超长会话压缩为后续可选项。

## 验证方式

1. `mvn package` → 两模块构建成功(JDK21)。
2. 启动(OS env 齐备)→ 日志确认 MCP 客户端连上 db-server、`invoice_agent_agent_state` 表自动创建。
3. 页面提问 → SSE 文本增量 + 工具状态可见;回答 4 段格式;三表数据正确;刷新后历史完整。
4. 双会话交叉访问 → 上下文互不串扰;越权 404。
