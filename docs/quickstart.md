# 快速上手:把 NL→SQL 账单分析 Agent 跑起来

从零把整个 Agent 在本机跑起来并验证。约 10 分钟(不含首次构建下载依赖)。

> 术语见 [`CONTEXT.md`](../CONTEXT.md):你输入的是**分析需求**,Agent 返回**结构化结果** + **业务解读**。

## 0. 跑起来是什么样

浏览器打开一个聊天页 → 用中文描述分析需求(如「2025 年各区域账单总额,按降序」)→ Agent 自主调用工具生成并执行 SQL → 返回**结构化结果**(汇总 + 表格)和**业务解读**。

数据是一个**示例账单库**(50 商户 / 2025-01 至 2025-12 / 含支付与退款),首次启动自动生成,无需手工建库。

架构(详见 [`README.md`](../README.md)):

```
浏览器(静态聊天页) ──POST /api/chat──▶ agent-app(Spring Boot 4.1 + Spring AI 2.0 + GLM)
                                          │ MCP / stdio(子进程)
                                   db-mcp-server(自建只读 MCP,4 工具 + 只读守卫)
                                          │ JDBC(只读)
                                   SQLite(~/.nl-sql-agent/bill.db)
```

## 1. 前置依赖

| 依赖 | 最低版本 | 验证 | 说明 |
|------|---------|------|------|
| JDK | **21+** | `java -version` | Spring Boot 4.1 要求;JBR 21 亦可 |
| Maven | **3.9+** | `mvn -v` | 或用 IDE 自带 |
| 智谱 GLM API Key | — | — | **必需**,否则无法对话;申请:https://open.bigmodel.cn |

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

## 3. 配置 API Key(必需)

```bash
export ZHIPU_API_KEY=你的智谱key      # bash / git-bash
```

Windows 其他 shell:

```cmd
set ZHIPU_API_KEY=你的智谱key              :: cmd
$env:ZHIPU_API_KEY="你的智谱key"           # PowerShell
```

未设置时配置项默认为 `dummy`,启动不报错,但**对话会被 GLM 拒绝(401)**。

## 4. 启动

⚠️ **必须在项目根目录 `nl-sql-agent/` 下执行**(子进程 jar 是相对路径 `./db-mcp-server/target/...`,见下文「环境变量」)。

```bash
java -jar agent-app/target/agent-app-0.1.0-SNAPSHOT.jar
```

启动时自动完成:

1. agent-app 以 stdio 子进程拉起 `db-mcp-server`(用同一个 JDK);
2. `db-mcp-server` 首次启动在 `~/.nl-sql-agent/bill.db` 建表并播种示例数据(merchant 为空才写);
3. MCP 握手完成,4 个只读工具就绪;
4. Web 服务监听 `:8080`。

看到「账单库已播种示例数据」与 Tomcat 监听 8080 的日志,即就绪。**保持此终端运行**(Ctrl+C 停止)。

## 5. 验证

**方式 A — 浏览器(推荐)**

打开 http://localhost:8080 ,在聊天框输入分析需求,等待 Agent 自主查库并返回 4 段回答(复述理解 / SQL / 结构化结果 / 业务解读)。

**方式 B — curl(非流式)**

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "2025年各区域账单总额,按降序"}'
```

返回:`{"conversationId":"...","content":"...4段回答..."}`。`conversationId` 可省略(自动生成),带上即可多轮追问(见下)。

## 6. 试问示例

示例数据覆盖 **2025-01 至 2025-12**。提问时把时间限定落在 2025 年内有结果:

- 2025 年各区域账单总额,按降序
- 2025 年 12 月退款率最高的 5 个商户
- 2025 年各月回款率(已结账单占比)趋势
- (接着上一条追问)只看华东 —— 带上上一次返回的 `conversationId` 即可

> ⚠️ 当前日期已晚于 2025。问「上月 / 最近 3 个月」会落在数据范围之外,可能查不到——Agent 会如实说明,这不是故障。

## 7. 可选配置(环境变量)

| 变量 | 默认 | 作用 |
|------|------|------|
| `ZHIPU_API_KEY` | `dummy` | GLM API Key(必需) |
| `ZHIPU_BASE_URL` | `https://open.bigmodel.cn/api/paas/v4/` | OpenAI 兼容端点 |
| `ZHIPU_MODEL` | `glm-4.6` | 模型名 |
| `DB_MCP_SERVER_JAR` | `./db-mcp-server/target/db-mcp-server-0.1.0-SNAPSHOT.jar` | 子进程 jar 路径(相对 → 依赖启动 CWD) |
| `APP_DB_PATH` | `~/.nl-sql-agent/bill.db` | 账单库路径(改它换库/换目录) |

> 注:`APP_DB_PATH` 是 Spring Boot 对配置项 `app.db.path` 的环境变量映射。`README.md` 里提到的 `DB_PATH` 当前**未在配置中绑定,不会生效**;要改库路径请用 `APP_DB_PATH` 或启动参数 `--app.db.path=...`。

多轮记忆窗口(默认保留最近 10 条)在 `agent-app/src/main/resources/application.yml` 的 `app.memory.max-messages`;**记忆在内存,重启即失**。

## 8. 常见排障

1. **`java`/`mvn` 版本不对或 `mvn: command not found`**
   确认 PATH 里是 JDK 21+ 与 Maven 3.9+。本机若默认是旧版(如 JDK 1.8、无 mvn),可用 IDE 自带:JDK 指向 `IntelliJ IDEA <ver>/jbr`(JBR 21,含 javac)、Maven 用 `<IDEA>/plugins/maven/lib/maven3/bin/mvn`。
   > 若用 RTK 代理 `mvn`,其框架输出(`BUILD SUCCESS` 等)会被过滤、只剩测试日志;拿完整输出用 `rtk proxy mvn <goals>`。

2. **对话报 401 / 不返回内容** → `ZHIPU_API_KEY` 未设置或无效。

3. **端口 8080 被占用** → 改 `agent-app/src/main/resources/application.yml` 的 `server.port`,或释放占用进程。

4. **启动报找不到 db-mcp-server jar** → 没在项目根目录启动,或未先执行第 2 步构建。检查 `DB_MCP_SERVER_JAR` 指向的文件存在。

5. **非 Windows 平台子进程拉起失败** → MCP 子进程命令写死为 `${java.home}/bin/java.exe`;Linux/macOS 需把 `agent-app/src/main/resources/application.yml` 里的 `java.exe` 改为 `java`。

6. **日志里看不到 db-mcp-server 输出** → 它是 stdio MCP server,日志全部走 stderr,stdout 留给 JSON-RPC。子进程的 stderr 会带到 agent-app 控制台。

## 9. 停止与重置

- **停止**:在运行 agent-app 的终端按 `Ctrl+C`(会一并终止 MCP 子进程)。
- **重置示例数据**:删除 `~/.nl-sql-agent/bill.db`,下次启动重新建库播种。
- **换一份真实数据**:把你的 SQLite 文件放到某路径,用 `APP_DB_PATH` 指向它;前提是表结构兼容(merchant / bill / payment / refund),否则工具查询会报错。
