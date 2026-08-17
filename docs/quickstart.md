# 快速上手:把 NL→SQL 账单分析 Agent 跑起来

从零把整个 Agent 在本机跑起来并验证。约 10 分钟(不含首次构建下载依赖)。

> 术语见 [`CONTEXT.md`](../CONTEXT.md):你输入的是**分析需求**,Agent 返回**结构化结果** + **业务解读**。

## 0. 跑起来是什么样

浏览器打开一个聊天页 → 登录 → 用中文描述分析需求(如「各区域账单总额,按降序」)→ Agent 自主调用工具生成并执行 SQL → 返回**结构化结果**(汇总 + 表格)和**业务解读**。

数据来自**已有的真实账单库**(MySQL `dst_db_bill`,只读账号),表结构与数据由外部提供,Agent 启动时**不建表播种**,而是用 `list_tables` 运行时动态发现。

架构(详见 [`README.md`](../README.md) / [`architecture.md`](./architecture.md)):

```
浏览器(登录页 + 聊天页) ──POST /api/login──► agent-app(Spring Boot 4.1 + AgentScope Java 2.0 + GLM)
                          ──POST /api/chat/stream(Bearer JWT,SSE 流式)──►     │ MCP / stdio(子进程)
                                                   db-mcp-server(自建只读 MCP,4 工具 + 只读守卫)
                                                                │ JDBC(只读账号 GRANT SELECT)
                                                   账单库 MySQL(dst_db_bill)
                                          agent-app 同时把会话/记忆写回 MySQL(dst_db_invoice)
```

## 1. 前置依赖

| 依赖 | 最低版本 | 验证 | 说明 |
|------|---------|------|------|
| JDK | **21+** | `java -version` | Spring Boot 4.1 要求;JBR 21 亦可 |
| Maven | **3.9+** | `mvn -v` | 或用 IDE 自带 |
| 智谱 GLM API Key | — | — | **必需**,否则无法对话;申请:https://open.bigmodel.cn |
| MySQL 账单库 | — | — | 已有库 `dst_db_bill` + 只读账号(只授 `SELECT`),含真实账单数据 |
| MySQL 记忆库 | — | — | 库 `dst_db_invoice` + 可写账号;Agent 在此建会话/记忆表(无需手工建表) |

> JDK 与 Maven 必须是 21+/3.9+ 的那套在 PATH 里。若默认 `java` 不是 21 或没有 `mvn`,见文末「排障」第 1 条。

## 2. 构建产物

在**项目根目录** `nl-sql-agent/` 下执行:

```bash
mvn clean package
```

产出两个 Spring Boot fat jar(含全部依赖,可直接 `java -jar`):

- `db-mcp-server/target/db-mcp-server-0.1.0-SNAPSHOT.jar`
- `agent-app/target/agent-app-0.1.0-SNAPSHOT.jar`

成功标志:末尾 `BUILD SUCCESS`,且两个 jar 都已生成。

## 3. 配置环境变量(必需)

所有凭证走环境变量,不写死。至少需配置 GLM key、两个 MySQL 库、JWT secret:

```bash
# GLM
export ZHIPU_API_KEY=你的智谱key

# 账单库(只读,已有数据)
export MYSQL_BILL_URL='jdbc:mysql://host:3306/dst_db_bill?useSSL=false&serverTimezone=Asia/Shanghai'
export MYSQL_BILL_USER=bill_query_account
export MYSQL_BILL_PASSWORD=...

# 记忆库(可写,需先 CREATE DATABASE dst_db_invoice;表由 Agent 启动时自动建)
export MEMORY_DB_URL='jdbc:mysql://host:3306/dst_db_invoice?useSSL=false&serverTimezone=Asia/Shanghai'
export MEMORY_DB_USER=...
export MEMORY_DB_PASSWORD=...

# JWT(至少 32 字符)
export JWT_SECRET=change-this-to-a-random-secret-at-least-32-chars-long
```

> `dst_db_invoice` 库需**先手工建**(`CREATE DATABASE dst_db_invoice`),Agent 启动时会在其中建 `invoice_agent_conversation` / `invoice_agent_message`(业务表,`spring.sql.init`,幂等)与 `invoice_agent_agent_state`(AgentScope 会话状态,自动建)三表。

## 4. 启动

⚠️ **必须在项目根目录 `nl-sql-agent/` 下执行**(子进程 jar 是相对路径 `./db-mcp-server/target/...`)。

```bash
java -jar agent-app/target/agent-app-0.1.0-SNAPSHOT.jar
```

启动时自动完成:

1. agent-app 以 stdio 子进程拉起 `db-mcp-server`(用同一个 JDK);
2. `db-mcp-server` 探活账单库连接,打印 `[init] 账单库连接成功 user=... 总表数=... 前15=[...]`(不再建表播种);
3. agent-app 在 `dst_db_invoice` 建记忆三表(若不存在);
4. MCP 握手完成,4 个只读工具就绪;
5. Web 服务监听 `:8080`。

看到账单库探活日志与 Tomcat 监听 8080,即就绪。**保持此终端运行**(Ctrl+C 停止)。

## 5. 验证

**方式 A — 浏览器(推荐)**

打开 http://localhost:8080 ,用预设账号登录(默认 `admin` / `admin123`,生产务必用 `ADMIN_PW_HASH` 覆盖),在聊天框输入分析需求,等待 Agent 自主查库并返回 4 段回答(复述理解 / SQL / 结构化结果 / 业务解读)。

**方式 B — curl(SSE 流式,需先登录拿 token)**

```bash
# 1) 登录,拿 JWT
TOKEN=$(curl -s -X POST http://localhost:8080/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

# 2) 带 token 提问(-N 关闭缓冲,实时看流)
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"message": "有哪些表,各多少行"}'
```

返回 SSE 事件流:`{"type":"start","conversationId":"..."}` → `{"type":"tool","name":"list_tables","state":"start|success"}`(工具进度)→ 连续 `{"type":"delta","text":"..."}`(文本增量,拼起来是 4 段回答)→ `{"type":"done"}`。不带 `Authorization` 头会直接 **401**。`conversationId` 可省略(自动生成),带上即可多轮追问。

## 6. 试问示例

表结构由 `list_tables` 运行时动态发现(真实账单库,不再是固定的演示表)。第一次提问可以直接说「有哪些表」,或直接描述需求——Agent 会自主调用 `list_tables` / `describe_table` 探明结构后再查。典型问法:

- 有哪些表,各多少行
- 某张表的字段含义和枚举取值
- 各区域/渠道/月份的账单总额,按降序
- (接着上一条追问)只看华东 —— 带上上一次返回的 `conversationId` 即可(记忆持久化,重启不丢)

> 提问前不必预知表名;若需求涉及的字段/枚举不存在,Agent 会基于真实表结构如实说明,这不是故障。

## 7. 可选配置(环境变量)

| 变量 | 默认 | 作用 |
|------|------|------|
| `ZHIPU_API_KEY` | (无,必填) | GLM API Key |
| `ZHIPU_BASE_URL` | `https://open.bigmodel.cn/api/coding/paas/v4/` | OpenAI 兼容端点(Coding Plan 套餐;按量付费 key 改为 `…/api/paas/v4/`) |
| `ZHIPU_MODEL` | `glm-5.2` | 模型名 |
| `MEMORY_DB_URL` / `MEMORY_DB_USER` / `MEMORY_DB_PASSWORD` | (必填) | 记忆库 `dst_db_invoice`(可写) |
| `MYSQL_BILL_URL` / `MYSQL_BILL_USER` / `MYSQL_BILL_PASSWORD` | (必填) | 账单库 `dst_db_bill`(只读) |
| `JWT_SECRET` | (内置占位,生产必填) | JWT 签名密钥,≥ 32 字符 |
| `ADMIN_PW_HASH` | admin/admin123 的 BCrypt | 覆盖默认 admin 密码 |
| `DB_MCP_SERVER_JAR` | `./db-mcp-server/target/db-mcp-server-0.1.0-SNAPSHOT.jar` | 子进程 jar 路径(相对 → 依赖启动 CWD) |

多轮记忆由 AgentScope 的 `MysqlAgentStateStore` 管理(按 `userId + conversationId` 分桶,保存完整对话上下文,无窗口截断);**持久化到中心 MySQL(`dst_db_invoice` 库),重启不丢**。排障时可设 `AI_LOG_LEVEL=DEBUG` 看 AgentScope 的 LLM 往返与工具调用明细。

## 8. 常见排障

1. **`java`/`mvn` 版本不对或 `mvn: command not found`**
   确认 PATH 里是 JDK 21+ 与 Maven 3.9+。本机若默认是旧版(如 JDK 1.8、无 mvn),可用 IDE 自带:JDK 指向 `IntelliJ IDEA <ver>/jbr`(JBR 21,含 javac)、Maven 用 `<IDEA>/plugins/maven/lib/maven3/bin/mvn`。
   > 若用 RTK 代理 `mvn`,其框架输出(`BUILD SUCCESS` 等)会被过滤、只剩测试日志;拿完整输出用 `rtk proxy mvn <goals>`。

2. **对话报 401 / 不返回内容** → `Authorization: Bearer <token>` 缺失或 token 过期;重新调 `/api/login` 拿 token。GLM 侧 401 则是 `ZHIPU_API_KEY` 未设置或无效。

3. **`[init] 账单库连接失败`** → 检查 `MYSQL_BILL_*` 与网络;确认账号对 `dst_db_bill` 有 `SELECT` 权限。

4. **记忆库建表/写入失败** → 确认 `MEMORY_DB_*` 指向的账号对 `dst_db_invoice` 可写,且该库已 `CREATE DATABASE`(Agent 只建表不建库)。

5. **端口 8080 被占用** → 改 `agent-app/src/main/resources/application.yml` 的 `server.port`,或释放占用进程。

6. **启动报找不到 db-mcp-server jar** → 没在项目根目录启动,或未先执行第 2 步构建。检查 `DB_MCP_SERVER_JAR` 指向的文件存在。

7. **非 Windows 平台子进程拉起失败** → MCP 子进程命令由 `AgentConfig` 按 `os.name` 自动选 `java.exe`/`java`(取自 `${java.home}`);若仍失败,检查该 JDK 的实际可执行文件名。

8. **日志里看不到 db-mcp-server 输出** → 它是 stdio MCP server,日志全部走 stderr,stdout 留给 JSON-RPC。子进程的 stderr 会带到 agent-app 控制台。

## 9. 停止与重置

- **停止**:在运行 agent-app 的终端按 `Ctrl+C`(会一并终止 MCP 子进程)。
- **清空会话/记忆**:清空 `dst_db_invoice` 里的 `invoice_agent_*` 三表(`TRUNCATE` 或 `DELETE`),下次对话重新累计;账单库 `dst_db_bill` 不受影响。
- **换账单数据源**:改 `MYSQL_BILL_*` 指向另一个只读 MySQL 账单库即可;表结构无需固定,Agent 会用 `list_tables` 动态发现。
