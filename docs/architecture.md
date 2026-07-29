# Agent 与大模型的完整链路

从「用户一句话」到「结构化结果 + 业务解读」的端到端链路。每个环节都标注了**对应的代码位置(文件:行 / 类.方法)与作用**,便于对着代码读。

> 路径简写:`agent-app/.../` = `agent-app/src/main/java/com/dstcar/nlsql/agent/`,`db-mcp-server/.../` = `db-mcp-server/src/main/java/com/dstcar/nlsql/dbmcp/`。
> 相关:[`README.md`](../README.md)、[`CONTEXT.md`](../CONTEXT.md)、[`quickstart.md`](./quickstart.md)、[`docs/adr/`](./adr/)。

## 一句话

用户中文需求进 HTTP 接口 → Spring AI 的 `ChatClient` 把【系统提示词 + 历史 + 工具定义】发给 GLM → GLM **自主决定**调哪些工具 → 经 MCP(stdio)在只读 MySQL 账单库(`dst_db_bill`)上执行 → 结果回拼再喂 GLM → 如此循环到 GLM 吐出最终文本 → 按系统提示词的 4 段格式返回。

## 端到端链路

```
浏览器                      agent-app (Spring Boot 4.1 + Spring AI 2.0)
 │  POST /api/login ──► JWT  │  ① AuthController: 预设账号校验 → 发 token
 │  POST /api/chat (Bearer)  │  ② ChatController: 收 {message} → [chat] 收到
 │  {message:"付款总金额"}     │     生成 conversationId
 │  ─────────────────────────► │
 │                             │  ③ ChatClient.call() 进入「工具调用循环」
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
 │                             │  ④ 执行工具 ──MCP stdio(JSON-RPC)──┐
 │                             │     │                              ▼
 │                             │     │            db-mcp-server (子进程)
 │                             │     │              ⑤ DbTools(4工具) + SqlSafetyGuard
 │                             │     │              ⑥ 只读账号(GRANT SELECT) ──► MySQL(dst_db_bill)
 │                             │     │              [tool] 入口/出口 日志
 │                             │     │ ◄─── 结构化结果(QueryResult) ─────────┘
 │                             │  ⑦ 结果作为 tool message 拼回对话 → 再发 GLM(下一轮)
 │                             │     ↻ 重复 ④~⑦,直到 GLM 不再要工具
 │                             │  ⑧ GLM 返回最终文本(4段格式) → 循环结束
 │                             │  ⑨ 记忆落库(JdbcChatMemoryRepository,持久化,窗口10条)
 │                             │  [chat] 完成 耗时
 │  {conversationId, content}  │
 │  ◄───────────────────────── │
```

## 代码索引(每个环节对应哪段代码)

| 环节 | 代码位置 | 作用 |
|---|---|---|
| ① 认证(轻量) | `agent-app/.../auth/AuthController.java:26` · `login()` + `JwtFilter.java:29` | `POST /api/login` 校验预设账号(BCrypt)发 JWT;`JwtFilter` 拦 `/api/*` 校验 `Bearer` token,把 userId 注入请求(ADR-0007) |
| ② HTTP 入口 | `agent-app/.../chat/ChatController.java:45` · `chat()` | 收 `POST /api/chat`(需 JWT),生成 `conversationId`,调 `ChatClient`,返 `{conversationId,content}`;`[chat]` 入口/出口/失败日志(`:55`/`:69`/`:74`) |
| ③ ChatClient 装配 | `agent-app/.../config/ChatClientConfig.java:31` · `chatClient()` | 把「系统提示词 + MCP 工具 + 记忆 advisor」组装成 `ChatClient` |
| ③ 系统提示词 | `ChatClientConfig.java:42` · `SYSTEM_PROMPT` | 定义工作流、**4 段回答格式**、反幻觉硬约束 |
| ③ 多轮记忆 | `ChatClientConfig.java:21` · `chatMemory()` + `:37` advisor | `MessageWindowChatMemory`(窗口 10);底层 `ChatMemoryRepository` 由 `memory/JdbcChatMemoryConfig.java:20` 提供为 `JdbcChatMemoryRepository`(持久化到 MySQL),按 `conversationId` 串联 |
| ③ GLM 调用配置 | `agent-app/src/main/resources/application.yml:11-18` | `base-url`(coding 端点)/`api-key`/`model`(glm-5.2)/`temperature`,走 OpenAI 兼容 |
| ④ MCP 子进程拉起 | `application.yml:25-33` (`mcp.client.stdio`) | agent-app 启动时 fork 出 db-mcp-server 子进程,stdio + JSON-RPC 通道 |
| ④ 工具注册给 MCP | `db-mcp-server/.../config/ToolConfig.java:20` · `dbToolCallbackProvider()` | 把 `DbTools` 的 `@Tool` 方法转成 `ToolCallbackProvider`,由 MCP server 暴露给 agent-app |
| ⑤ list_tables | `db-mcp-server/.../tools/DbTools.java:48` · `listTables()` | 列所有用户表 + 中文注释 + 近似行数(识别分表组,提示 UNION ALL) |
| ⑤ describe_table | `DbTools.java:78` · `describeTable()` | 表结构:列(名/类型/可空/主键/中文注释)+ 外键(读 `INFORMATION_SCHEMA`) |
| ⑤ sample_data | `DbTools.java:130` · `sampleData()` | 前若干行真实样本(映射枚举值) |
| ⑤ run_readonly_sql | `DbTools.java:144` · `runReadonlySql()` | 执行单条 SELECT;前置守卫 + 上限保护 |
| ⑥ 只读守卫(解析层) | `db-mcp-server/.../guard/SqlSafetyGuard.java:29` · `validate()` | 非空、单条(禁 `;`)、首关键字 ∈ {SELECT,WITH}、token 级禁写/DDL 关键字(黑名单 `:18`) |
| ⑥ 只读(连接层) | `DbTools.java:205` · `openReadonlyConnection()` | 只读由 MySQL 账号权限保证(`GRANT SELECT`,ADR-0006);不再用 SQLite 的 `PRAGMA query_only` |
| ⑥ 上限保护 | `DbTools.java:161` · `executeCapped()` + `config/DbProperties.java` | 行 ≤ 1000 / 超时 ≤ 10s / 体积 ≤ 1MB,超限 `truncated` |
| ⑧ 账单库探活 | `db-mcp-server/.../db/DatabaseInitializer.java:34` · `run()` | 启动探活 `dst_db_bill` 连接并打印实际表清单(接真实 MySQL,**不再建表播种**,ADR-0006) |
| 记忆/会话库 | `agent-app/.../conversation/ConversationRepository.java` + `memory/JdbcChatMemoryConfig.java` | 三表同在 `dst_db_invoice` 库:`invoice_agent_chat_memory`(LLM 上下文)、`invoice_agent_conversation`/`invoice_agent_message`(会话元数据 + 前端全量历史) |
| 日志路由 | `db-mcp-server/src/main/resources/logback-spring.xml` | 全走 stderr(stdout 留给 JSON-RPC);`com.dstcar`=INFO |

## 一次「付款总金额」的生命周期(典型多轮)

| 轮 | 动作 | 组件(代码) | 关键点 |
|---|---|---|---|
| 0 | 登录拿 token,`POST /api/chat`(带 `Bearer`)进来,生成 `conversationId` | `AuthController.java:25` + `ChatController.java:45` | 打 `[chat] 收到`(`:55`) |
| 1 | 组装请求:**系统提示词** + 历史(首次空) + `付款总金额` + **4 工具定义** → 发 GLM | `ChatClientConfig.java:31` + `:42` + `:37` | 工具定义来自 MCP 的 `ToolCallbackProvider`(`ToolConfig.java:20`) |
| 2 | GLM 返回 `tool_calls:[list_tables]` | GLM(Spring AI 内部) | GLM **自己决定**先看有哪些表 |
| 3 | 执行 `list_tables` → 经 MCP stdio → 只读查 MySQL(`dst_db_bill`)→ 返回实际表 | `DbTools.java:48` | 打 `[tool] list_tables 完成 表数=N` |
| 4 | 结果回拼 → 再发 GLM;返回 `[describe_table xxx]` 或直接 `[run_readonly_sql "SELECT SUM(amount) FROM ..."]` | GLM | 按需探索表结构/枚举值 |
| 5 | 执行 SQL:`SqlSafetyGuard` 校验 → 只读账号执行 → 上限保护 → 返回结果 | `DbTools.java:144` + `SqlSafetyGuard.java:29` + `:161` | 打 `[tool] run_readonly_sql sql="..." 返回行数=N` |
| 6 | 结果回拼 → 发 GLM → GLM 返回**最终文本**(无 `tool_calls`)→ 循环结束 | GLM | 文本是 4 段:复述/SQL/结构化结果/业务解读 |
| 7 | 本轮消息写入记忆(`invoice_agent_message` 全量 + `invoice_agent_chat_memory` 上下文窗口);返回 `{conversationId, content}` | `ChatController.java:66` | 打 `[chat] 完成 耗时=...ms`(`:69`) |

> 即 ADR-0004 的「**工具调用循环,而非固定流水线**」——不是「取schema→生成SQL→执行→解读」的写死步骤,而是 GLM 看到工具报错会自己改 SQL 重试,天然支持自纠错。

## 横切机制

### ① agent loop(`ChatClient.call()` 内部)
同步非流式,触发点在 `ChatController.java:59` `.call()`。每轮:发请求 → 看 GLM 返回是 `tool_calls` 还是最终文本 → 是工具就执行、把结果作为 `tool` 角色消息拼回去再发 → 直到无 `tool_calls`。`AI_LOG_LEVEL=DEBUG`(`application.yml:60`)能看到这一层的决策与往返。

### ② MCP 作为工具传输层
agent-app 是 MCP **client**,db-mcp-server 是 MCP **server**(stdio 传输,`application.yml:25-33`)。agent-app 启动时拉起子进程、握手,工具经 `ToolConfig.java:20` 注册、由 MCP 暴露,注入 `ChatClient`(`ChatClientConfig.java:37`)。调用经 stdin/stdout 的 **JSON-RPC** 往返。
⚠️ db-mcp-server 的 stdout 是协议通道,**日志全走 stderr**(`logback-spring.xml` 强制),否则破坏握手。

### ③ 只读纵深防御(ADR-0003 / 0006,三层)
- **解析层** `SqlSafetyGuard.java:29`:非空、单条(禁 `;`)、首关键字 ∈ {SELECT,WITH}、token 级禁一切写/DDL/PRAGMA 关键字(黑名单 `:18`,含 MySQL 方言关键字);失败抛异常 → 被当工具错误回告 GLM。
- **连接层** `DbTools.java:205`:只读由 **MySQL 账号权限**保证(`bill_query_account` 只 `GRANT SELECT`,DB 权限侧硬兜底,等价于 SQLite 的连接级只读);JDBC URL 不开 `allowMultiQueries`(避免绕过单条防线)。
- **上限层** `DbTools.java:161`:行 ≤ 1000 / 超时 ≤ 10s / 体积 ≤ 1MB,超限 `truncated`(配置在 `DbProperties.java`)。

### ④ 多轮记忆(ADR-0007,持久化)
`ChatClientConfig.java:21` `MessageWindowChatMemory`(窗口 10),底层 `ChatMemoryRepository` 由 `memory/JdbcChatMemoryConfig.java:20` 装配为 `JdbcChatMemoryRepository`(持久化到中心 MySQL 的 `dst_db_invoice` 库,**重启不丢**)。表名由 `InvoiceAgentMysqlChatMemoryRepositoryDialect` 固定为 `invoice_agent_chat_memory`(Spring AI 2.0 无 table-name 配置项,见 ADR-0007 实施注记),按 `conversationId` 串联。所以「只看华东」这种追问能结合上一条上下文。

### ⑤ 可观测性
`[chat]` 入口/出口/失败(`ChatController.java:55/69/74`)+ `[tool]`/`[guard]`/`[init]` 各节点(`DbTools`/`SqlSafetyGuard`/`DatabaseInitializer` 各方法 + `logback-spring.xml`)。对外行为不变(异常仍 500/401,只是补了关联日志)。

## 边界提醒

- **LLM 是黑盒**:工具调用决策、SQL 生成、往返次数都在 Spring AI 内部 + GLM 那侧,代码只在入口(`ChatController`)和工具(`DbTools`)两端能插桩;中间靠 `AI_LOG_LEVEL=DEBUG` 看。
- **记忆持久但窗口有限**:上下文已持久化到中心 MySQL(`dst_db_invoice` 库),重启不丢;但喂给 LLM 的仅最近 10 条(`MessageWindow`),前端历史另存全量(`invoice_agent_message` 表)。
- **账单库只读且外部提供**:`dst_db_bill` 由外部提供,Agent 只读(`GRANT SELECT`),启动不建表播种;表结构由 `list_tables` 运行时动态发现。
- **模型/端点**:`glm-5.2` + `coding/paas/v4/`(GLM Coding Plan 套餐);换按量付费 key 要改回 `paas/v4/`(见 [`quickstart.md`](./quickstart.md) 第 7 节)。
