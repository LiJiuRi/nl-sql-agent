# NL→SQL 账单分析 Agent

中文自然语言数据分析 Agent:用户用中文描述分析需求,Agent 自主生成 SQL、经自建只读 DB MCP server 在 SQLite 上执行,返回**结构化结果**并给出**业务解读**。

## 架构

```
浏览器(静态 HTML/JS 聊天页)
        │ POST /api/chat (非流式)
[ agent-app ]  Spring Boot 4.1 + Spring AI 2.0
   · GLM(经 OpenAI 兼容端点)  · 工具调用循环  · 多轮记忆(内存,最近10条)
        │ MCP / stdio(子进程)
[ db-mcp-server ]  自建只读 MCP 服务
   · 4 工具: list_tables / describe_table / sample_data / run_readonly_sql
   · 安全: 连接级只读(PRAGMA query_only)+ 解析层单 SELECT + 上限(1000行/10s/1MB)
        │ JDBC(只读)
   SQLite  merchant / bill / payment / refund(跨12个月示例数据)
```

决策见 [`CONTEXT.md`](./CONTEXT.md)(术语)与 [`docs/adr/`](./docs/adr)(6 条架构决策)。

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

```bash
# 在 nl-sql-agent/ 目录下:
export ZHIPU_API_KEY=你的智谱key      # 必需,否则无法对话
java -jar agent-app/target/agent-app-0.1.0-SNAPSHOT.jar
# agent-app 会自动以子进程拉起 db-mcp-server;
# 首次启动在 ~/.nl-sql-agent/bill.db 建库并播种示例数据(50 商户 / ~1220 账单 / 支付 / 退款)。
```

浏览器打开 http://localhost:8080

可选环境变量:`ZHIPU_BASE_URL`、`ZHIPU_MODEL`(默认 glm-4.6)、`DB_MCP_SERVER_JAR`、`DB_PATH`。

## 验证状态(2026-07-24)

| 项 | 状态 |
|----|------|
| `mvn clean package`(编译+打包) | ✅ 通过 |
| 单元测试 `DbToolsTest`(4 个:工具逻辑 + 只读守卫) | ✅ 4/4 通过 |
| agent-app 启动 → MCP 子进程握手 → 4 工具就绪 → Web UI(:8080) | ✅ 已验证 |
| 真实 GLM 推理(端到端对话) | ⏳ 需有效 API Key 才能验证 |

## 试问示例

- 上月退款率最高的 5 个商户
- 各区域本年账单总额,按降序
- 最近 3 个月回款率(已结账单占比)趋势
- 只看华东(接着上一条追问)
