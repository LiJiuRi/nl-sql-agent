# NL→SQL 账单分析 Agent

中文自然语言数据分析 Agent:用户用中文描述分析需求,Agent 自主生成 SQL、经自建只读 DB MCP server 在 SQLite 上执行,返回**结构化结果**并给出**业务解读**。

## 架构

```
浏览器(静态 HTML/JS 聊天页,SSE 流式渲染)
        │ POST /api/chat/stream (SSE: 文本增量 + 工具状态)
[ agent-app ]  Spring Boot 4.1 + AgentScope Java 2.0
   · GLM(经 OpenAI 兼容端点 + GLMFormatter)  · ReActAgent 工具调用循环
   · 多轮记忆: MysqlAgentStateStore(按 userId+conversationId 分桶)
        │ MCP / stdio(子进程)
[ db-mcp-server ]  自建只读 MCP 服务
   · 4 工具: list_tables / describe_table / sample_data / run_readonly_sql
   · 安全: 只读账号 + 解析层单 SELECT + 上限(1000行/10s/1MB)
        │ JDBC(只读)
   MySQL  dst_db_bill(账单库,203 张业务表)
```

决策见 [`CONTEXT.md`](./CONTEXT.md)(术语)与 [`docs/adr/`](./docs/adr)(8 条架构决策)。

## 前置

- **JDK 21+**(本仓库用 IntelliJ 自带 JBR 21)
- **Maven 3.9+**(或 IDE 自带)
- **智谱 GLM API Key**(https://open.bigmodel.cn)

## 构建

```bash
mvn -f nl-sql-agent/pom.xml clean package
# 产出:
#   db-mcp-server/target/db-mcp-server-0.1.0-SNAPSHOT.jar
#   agent-app/target/agent-app-0.1.0-SNAPSHOT.jar
```

## 运行

完整步骤(环境变量、双 MySQL 库、排障)见 [`docs/quickstart.md`](./docs/quickstart.md)。最小启动:

```bash
# 在 nl-sql-agent/ 目录下(子进程 jar 是相对路径):
export ZHIPU_API_KEY=你的智谱key      # 必需
export MYSQL_BILL_URL=... MYSQL_BILL_USER=... MYSQL_BILL_PASSWORD=...   # 只读账单库
export MEMORY_DB_URL=... MEMORY_DB_USER=... MEMORY_DB_PASSWORD=...      # 可写记忆库
export JWT_SECRET=...(≥32 字符)
java -jar agent-app/target/agent-app-0.1.0-SNAPSHOT.jar
# agent-app 自动以 stdio 子进程拉起 db-mcp-server,并在记忆库建 invoice_agent_* 表。
```

浏览器打开 http://localhost:8080(默认 admin/admin123)

可选环境变量:`ZHIPU_BASE_URL`、`ZHIPU_MODEL`(默认 glm-5.2)、`DB_MCP_SERVER_JAR`。

## 验证状态(2026-08-17,AgentScope 2.0 重写后)

| 项 | 状态 |
|----|------|
| `mvn clean package`(编译+打包) | ✅ 通过 |
| agent-app 启动 → MCP 子进程握手 → 4 工具就绪 → 状态表自动创建 | ✅ 已验证 |
| SSE 流式对话(文本增量 + 工具状态,端到端) | ✅ 已验证(真实 GLM) |
| 多轮上下文 / 会话隔离 / 越权 404 / 历史刷新恢复 | ✅ 已验证 |

## 试问示例

- 上月退款率最高的 5 个商户
- 各区域本年账单总额,按降序
- 最近 3 个月回款率(已结账单占比)趋势
- 只看华东(接着上一条追问)
