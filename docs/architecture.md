# Agent 与大模型的完整链路

从「用户一句话」到「结构化结果 + 业务解读」的端到端链路。每个环节都标注了**对应的代码位置(文件:行 / 类.方法)与作用**,便于对着代码读。

> 路径简写:`agent-app/.../` = `agent-app/src/main/java/com/dstcar/nlsql/agent/`,`db-mcp-server/.../` = `db-mcp-server/src/main/java/com/dstcar/nlsql/dbmcp/`。
> 相关:[`README.md`](../README.md)、[`CONTEXT.md`](../CONTEXT.md)、[`quickstart.md`](./quickstart.md)、[`docs/adr/`](./adr/)。

## 一句话

用户中文需求进 HTTP 接口 → Spring AI 的 `ChatClient` 把【系统提示词 + 历史 + 工具定义】发给 GLM → GLM **自主决定**调哪些工具 → 经 MCP(stdio)在只读 SQLite 上执行 → 结果回拼再喂 GLM → 如此循环到 GLM 吐出最终文本 → 按系统提示词的 4 段格式返回。

## 端到端链路

```
浏览器                      agent-app (Spring Boot 4.1 + Spring AI 2.0)
 │                             │
 │  POST /api/chat             │  ① ChatController: 收 {message} → [chat] 收到
 │  {message:"付款总金额"}     │     生成 conversationId
 │  ─────────────────────────► │
 │                             │  ② ChatClient.call() 进入「工具调用循环」
 │                             │     payload = 系统提示词 + 历史(记忆) + user msg + 4工具定义
 │                             │     │
 │                             │     │  HTTPS(OpenAI 兼容)
 │                             │     ▼
 │                             │   ┌──────────────┐
 │                             │   │  智谱 GLM    │  glm-5.2, temp=0.2
 │                             │   │  (coding端点) │
 │                             │   └──────┬───────┘
 │                             │          │ 返回 tool_calls(如: list_tables)
 │                             │     │◄───┘
 │                             │  ③ 执行工具 ──MCP stdio(JSON-RPC)──┐
 │                             │     │                              ▼
 │                             │     │            db-mcp-server (子进程)
 │                             │     │              ④ DbTools(4工具) + SqlSafetyGuard
 │                             │     │              ⑤ 只读连接(PRAGMA query_only) ──► SQLite
 │                             │     │              [tool] 入口/出口 日志
 │                             │     │ ◄─── 结构化结果(QueryResult) ─────────┘
 │                             │  ⑥ 结果作为 tool message 拼回对话 → 再发 GLM(下一轮)
 │                             │     ↻ 重复 ③~⑥,直到 GLM 不再要工具
 │                             │  ⑦ GLM 返回最终文本(4段格式) → 循环结束
 │                             │  ⑧ 记忆存档(MessageWindow,最近10条)
 │                             │  [chat] 完成 耗时
 │  {conversationId, content}  │
 │  ◄───────────────────────── │
```

## 代码索引(每个环节对应哪段代码)

| 环节 | 代码位置 | 作用 |
|---|---|---|
| ① HTTP 入口 | `agent-app/.../chat/ChatController.java:37` · `chat()` | 收 `POST /api/chat`,生成 `conversationId`,调 `ChatClient`,返 `{conversationId,content}`;`[chat]` 入口/出口/失败日志 |
| ② ChatClient 装配 | `agent-app/.../config/ChatClientConfig.java:34` · `chatClient()` | 把「系统提示词 + MCP 工具 + 记忆 advisor」组装成 `ChatClient` |
| ② 系统提示词 | `ChatClientConfig.java:46` · `SYSTEM_PROMPT` | 定义工作流、**4 段回答格式**、反幻觉硬约束 |
| ② 多轮记忆 | `ChatClientConfig.java:25` · `chatMemory()` + `:41` advisor | `MessageWindowChatMemory`(窗口 10)+ `JdbcChatMemoryRepository`(持久化到 MySQL),按 `conversationId` 串联 |
| ② GLM 调用配置 | `agent-app/src/main/resources/application.yml:5-10` | `base-url`(coding 端点)/`api-key`/`model`(glm-5.2)/`temperature`,走 OpenAI 兼容 |
| ③ MCP 子进程拉起 | `application.yml:11-20` (`mcp.client.stdio`) | agent-app 启动时 fork 出 db-mcp-server 子进程,stdio + JSON-RPC 通道 |
| ③ 工具注册给 MCP | `db-mcp-server/.../config/ToolConfig.java:20` · `dbToolCallbackProvider()` | 把 `DbTools` 的 `@Tool` 方法转成 `ToolCallbackProvider`,由 MCP server 暴露给 agent-app |
| ④ list_tables | `db-mcp-server/.../tools/DbTools.java:46` · `listTables()` | 列所有表 + 中文注释 + 近似行数 |
| ④ describe_table | `DbTools.java:60` · `describeTable()` | 表结构:列(名/类型/可空/主键/注释)+ 外键 |
| ④ sample_data | `DbTools.java:99` · `sampleData()` | 前若干行真实样本(映射枚举值) |
| ④ run_readonly_sql | `DbTools.java:113` · `runReadonlySql()` | 执行单条 SELECT;前置守卫 + 上限保护 |
| ⑤ 只读守卫(解析层) | `db-mcp-server/.../guard/SqlSafetyGuard.java:26` · `validate()` | 非空、单条(禁 `;`)、首关键字 ∈ {SELECT,WITH}、token 级禁写/DDL 关键字 |
| ⑤ 只读(连接层) | `DbTools.java:174` · `openReadonlyConnection()`(`:177`) | `PRAGMA query_only=ON`,连接级拒绝一切写/DDL |
| ⑤ 上限保护 | `DbTools.java:130` · `executeCapped()` + `config/DbProperties.java` | 行 ≤ 1000 / 超时 ≤ 10s / 体积 ≤ 1MB,超限 `truncated` |
| ⑦ 建库播种 | `db-mcp-server/.../db/DatabaseInitializer.java:42` · `run()` | 启动建 4 表(merchant/bill/payment/refund)+ 首次播种示例数据(`seed:125`) |
| 日志路由 | `db-mcp-server/src/main/resources/logback-spring.xml` | 全走 stderr(stdout 留给 JSON-RPC);`com.dstcar`=INFO |

## 一次「付款总金额」的生命周期(典型多轮)

| 轮 | 动作 | 组件(代码) | 关键点 |
|---|---|---|---|
| 0 | `POST /api/chat` 进来,生成 `conversationId` | `ChatController.java:37` | 打 `[chat] 收到` |
| 1 | 组装请求:**系统提示词** + 历史(首次空) + `付款总金额` + **4 工具定义** → 发 GLM | `ChatClientConfig.java:34` + `:46` + `:41` | 工具定义来自 MCP 的 `ToolCallbackProvider`(`ToolConfig.java:20`) |
| 2 | GLM 返回 `tool_calls:[list_tables]` | GLM(Spring AI 内部) | GLM **自己决定**先看有哪些表 |
| 3 | 执行 `list_tables` → 经 MCP stdio → 只读查 SQLite → 返回 4 表 | `DbTools.java:46` | 打 `[tool] list_tables 完成 表数=4` |
| 4 | 结果回拼 → 再发 GLM;返回 `[describe_table payment]` 或直接 `[run_readonly_sql "SELECT SUM(amount) FROM payment"]` | GLM | 按需探索表结构/枚举值 |
| 5 | 执行 SQL:`SqlSafetyGuard` 校验 → 只读连接执行 → 上限保护 → 返回 1 行(总额) | `DbTools.java:113` + `SqlSafetyGuard.java:26` + `:130` | 打 `[tool] run_readonly_sql sql="..." 返回行数=1` |
| 6 | 结果回拼 → 发 GLM → GLM 返回**最终文本**(无 `tool_calls`)→ 循环结束 | GLM | 文本是 4 段:复述/SQL/结构化结果/业务解读 |
| 7 | 本轮消息写入记忆;返回 `{conversationId, content}` | `ChatController.java:54` | 打 `[chat] 完成 耗时=...ms` |

> 即 ADR-0004 的「**工具调用循环,而非固定流水线**」——不是「取schema→生成SQL→执行→解读」的写死步骤,而是 GLM 看到工具报错会自己改 SQL 重试,天然支持自纠错。

## 横切机制

### ① agent loop(`ChatClient.call()` 内部)
同步非流式,触发点在 `ChatController.java:48` `.call()`。每轮:发请求 → 看 GLM 返回是 `tool_calls` 还是最终文本 → 是工具就执行、把结果作为 `tool` 角色消息拼回去再发 → 直到无 `tool_calls`。`AI_LOG_LEVEL=DEBUG`(`application.yml:31`)能看到这一层的决策与往返。

### ② MCP 作为工具传输层
agent-app 是 MCP **client**,db-mcp-server 是 MCP **server**(stdio 传输,`application.yml:11-20`)。agent-app 启动时拉起子进程、握手,工具经 `ToolConfig.java:20` 注册、由 MCP 暴露,注入 `ChatClient`(`ChatClientConfig.java:40`)。调用经 stdin/stdout 的 **JSON-RPC** 往返。
⚠️ db-mcp-server 的 stdout 是协议通道,**日志全走 stderr**(`logback-spring.xml` 强制),否则破坏握手。

### ③ 只读纵深防御(ADR-0003,三层)
- **解析层** `SqlSafetyGuard.java:26`:非空、单条(禁 `;`)、首关键字 ∈ {SELECT,WITH}、token 级禁一切写/DDL/PRAGMA 关键字(黑名单 `:18`);失败抛异常 → 被当工具错误回告 GLM。
- **连接层** `DbTools.java:174`:`PRAGMA query_only=ON`,JDBC 连接级拒绝一切写/DDL(就算 SQL 绕过了解析也写不进去)。
- **上限层** `DbTools.java:130`:行 ≤ 1000 / 超时 ≤ 10s / 体积 ≤ 1MB,超限 `truncated`(配置在 `DbProperties.java`)。

### ④ 多轮记忆
`ChatClientConfig.java:25` `MessageWindowChatMemory`(窗口 10)+ `:20` `JdbcChatMemoryRepository`(持久化到中心 MySQL 的 `dst_db_invoice` 库,**重启不丢**;表名由 `InvoiceAgentMysqlChatMemoryRepositoryDialect` 固定为 `invoice_agent_chat_memory`),按 `conversationId` 串联。所以「只看华东」这种追问能结合上一条上下文。

### ⑤ 可观测性
`[chat]` 入口/出口/失败(`ChatController.java:42/51/57`)+ `[tool]`/`[guard]` 各节点(`DbTools` 各方法 + `logback-spring.xml`)。对外行为不变(异常仍 500,只是补了关联日志)。

## 边界提醒

- **LLM 是黑盒**:工具调用决策、SQL 生成、往返次数都在 Spring AI 内部 + GLM 那侧,代码只在入口(`ChatController`)和工具(`DbTools`)两端能插桩;中间靠 `AI_LOG_LEVEL=DEBUG` 看。
- **记忆持久但窗口有限**:上下文已持久化到中心 MySQL(`dst_db_invoice` 库),重启不丢;但喂给 LLM 的仅最近 10 条(`MessageWindow`),前端历史另存全量(`invoice_agent_message` 表)。
- **模型/端点**:`glm-5.2` + `coding/paas/v4/`(GLM Coding Plan 套餐);换按量付费 key 要改回 `paas/v4/`(见 [`quickstart.md`](./quickstart.md) 第 7 节)。
