# NL2SQL 增强设计文档

> 三项增强：SQL 错误结构化回灌闭环 · Schema TTL 缓存 · 意图守卫与重试契约
> 涉及模块：`db-mcp-server`、`agent-app`
> 日期：2026-08-05

---

## 1. 背景与目标

`agent-app` 的 NL2SQL 能力基于 Spring AI 2.0 + GLM，通过 MCP 协议调用 `db-mcp-server` 的只读工具完成「自然语言 → SQL → 结果 → 业务解读」。当前实现把生成正确性完全压在「一次 `ChatClient.call()` + 一段 `SYSTEM_PROMPT`」上，存在三处明显短板（详见 §2）。

本设计借鉴 [DataAgent](https://github.com/spring-ai-alibaba/DataAgent)（基于 Spring AI Alibaba Graph 的企业级数据 Agent）的「显式校验闭环 / Schema 召回 / 意图守卫」思想，但**不引入 StateGraph 或任何新框架**——`agent-app` 是单库只读 SELECT 分析，LLM 自主 tool-calling 的轻量范式本身是合适的，真正短板在于缺少显式的质量闭环与元数据缓存。因此我们在现有 `ChatClient` + MCP 范式内做最小增量。

**目标**：
1. SQL 失败时有结构化纠错闭环（基于错误文本重试，上限 3 次，禁止编造）；
2. Schema 元数据查询命中 TTL 缓存，减少对只读库的重复 `INFORMATION_SCHEMA` 查询；
3. 无关闲聊 / 模糊歧义 / 超出能力边界的请求在生成 SQL 前被前置拦截。

---

## 2. 现状与短板

### 2.1 架构现状

```
用户 → POST /api/chat (agent-app, ChatController)
        └─ ChatClient(SYSTEM_PROMPT + MCP 工具 + MessageChatMemoryAdvisor)
             └─ LLM(GLM-5.2) 自主多步 tool calling,一次 .call() 完成
                  └─ MCP stdio → db-mcp-server 子进程
                       ├─ list_tables      表清单+注释+行数+分表识别
                       ├─ describe_table   列+外键关系+注释
                       ├─ sample_data      样本数据
                       └─ run_readonly_sql SqlSafetyGuard 守卫 → 受限执行
```

- `agent-app`：`ChatClientConfig` 装配 `SYSTEM_PROMPT` + MCP 工具 + 记忆；`ChatController.chat()` 非流式，LLM 在 `.call()` 内部黑盒式自主调工具。
- `db-mcp-server`：独立 stdio 子进程，只读 MySQL 账号（`GRANT SELECT`）+ `SqlSafetyGuard` 解析层守卫，构成纵深防御。

### 2.2 三处短板

| # | 短板 | 根因 |
|---|---|---|
| 1 | **SQL 纠错无保障** | `run_readonly_sql` 执行失败抛裸异常，`SYSTEM_PROMPT` 仅一句「若报错自行修正重试」，是否重试/重试几次全凭 LLM 心情，无结构、无上限。 |
| 2 | **Schema 反复查库** | LLM 每轮自主调 `list_tables`/`describe_table`，每次都打只读库（`INFORMATION_SCHEMA` + `COUNT(*)`），慢且重复。 |
| 3 | **无意图守卫** | 闲聊/模糊/不可答问题直接进 SQL 生成流，浪费 tool calling 甚至产出错误 SQL。 |

---

## 3. 设计原则

1. **借鉴思想，不搬框架**：学 DataAgent 的「显式校验闭环 / 召回 / 守卫」，但不引入 StateGraph（那是为 SQL+Python+Report 多模态复杂规划服务的，对单库只读场景属过度设计）。
2. **只做必要改动**：每行变更可追溯到上述短板；遵循现有代码风格与边界。
3. **保留现有范式**：不改变 LLM 自主 tool-calling 的调用结构，只在工具层与 prompt 层做增量。

---

## 4. 方案一：SQL 错误结构化回灌（轻量增强）

### 4.1 DataAgent 对应思想
DataAgent 用 `SqlGenerateNode → SemanticConsistencyNode → SqlExecuteNode` 三节点 + 条件 retry 边，把「生成 → 自检 → 执行 → 纠错」显式化。`agent-app` 不上节点图，但可借鉴其核心：**让执行失败以结构化形式回灌给 LLM，并建立硬重试契约**。

### 4.2 设计

**(a) 返回结构加 `error` 字段**：`QueryResult` 新增 `String error`（`null`=成功，非空=失败原因）。这是 db-mcp↔agent-app 间的契约，通过 MCP 序列化传递，加字段向后兼容。

**(b) 工具错误不再抛异常，改为结构化返回**：
- `SqlSafetyGuard` 拒绝（写操作/危险关键字/多语句）→ 返回 `QueryResult(error="SQL 被安全规则拒绝: " + 原因)`；
- `executeCapped` 的 `SQLException`（执行失败）→ 返回 `QueryResult(error="执行失败: " + DB 错误文本)`。

错误文本天然回灌给 LLM（Spring AI tool calling 框架把工具返回传回模型），LLM 据此判断是语法错、语义错还是被安全规则拒绝。

**(c) SYSTEM_PROMPT 硬重试契约**：显式约束「返回 error 时必须基于 error 修正重试，上限 3 次；3 次仍失败则如实告知原因 + 最后一条 SQL，严禁编造结果」。

### 4.3 改动点

| 文件 | 改动 |
|---|---|
| `db-mcp/model/QueryResult.java` | record 末尾加 `String error` 字段 + 注释 |
| `db-mcp/tools/DbTools.java` `runReadonlySql` | `guard.validate` 的 `catch(IllegalArgumentException)` 改为返回结构化 error（不再向上抛） |
| `db-mcp/tools/DbTools.java` `executeCapped` | `catch(SQLException)` 改为返回结构化 error；成功路径补 `error=null`；失败打 `[sql-fail]` 日志 |

**范围克制**：只改 `run_readonly_sql` 错误路径（重试闭环核心）；`describe_table`/`sample_data`/`requireTable` 的异常保持现状（探索类工具，异常同样会被框架回灌，不在闭环范围内）。

### 4.4 时序

```
LLM 生成 SQL → run_readonly_sql
                 ├─ guard.validate 拒绝? → 返回 {error:"...安全规则拒绝..."} ──┐
                 └─ executeCapped 执行                                        │
                      ├─ 成功 → 返回 {columns,rows,..., error:null}           │
                      └─ SQLException → 返回 {error:"...执行失败..."} ────────┤
LLM 收到 error(非空) → 阅读 error、修正 SQL、重试(≤3 次) ◄─────────────────────┘
LLM 3 次仍失败 → 如实告知用户,不编造
```

### 4.5 取舍：为何不做「受控循环（1b）」
另一种更彻底的做法是解耦「生成」与「执行」——LLM 只产出候选 SQL，由 `agent-app` 显式跑 `校验→执行→错误回灌→重生成` 受控循环，重试上限硬编码。**代价**：放弃 LLM 对最终 SQL 执行的自主性、改动大、丢失自主探索的灵活性。本次采用轻量增强（错误结构化 + prompt 契约），保留 LLM 自主性；仅当线上观察到 LLM 死循环/不收敛时，再考虑升级为受控循环。

---

## 5. 方案二：Schema TTL 缓存

### 5.1 DataAgent 对应思想
DataAgent 用 `SchemaRecallNode`（按问题召回相关表 DDL）+ `TableRelationNode`（推理 JOIN 路径）显式管理 schema。`agent-app` 的 schema 数据其实已通过 `describe_table` 的外键返回暴露给 LLM，缺的不是数据，而是**缓存与减少重复查库**。

### 5.2 设计

**(a) 零依赖 TTL 缓存 `SchemaCache`**：`ConcurrentHashMap<key, Entry(value, expireAt)>`，提供 `get(key, ttlSeconds, loader)`；`ttlSeconds <= 0` 直通不缓存；过期项惰性淘汰（下次 `get` 重新加载）。不引入 Caffeine（db-mcp 无该依赖，避免新增）。

**(b) 配置项**：`DbProperties` 加 `schemaCacheTtlSeconds`（默认 600 秒 = 10 分钟；置 0 关闭）。

**(c) DbTools 接入缓存**：
- `existingTables()`（表名集合）整体缓存；
- `listTables()` 最终 `List<TableInfo>` 整体缓存（最有效：命中即跳过逐表 `tableComment`/`approxRowCount`）；
- `describeTable()` 按表名缓存 `TableSchema`；
- `sample_data` **不缓存**（要看实时样本）。

实现上把原方法体抽成 `buildListTables`/`loadTableSchema`/`loadExistingTables` 私有方法，公共方法包一层缓存。`requireTable`（表名校验）保留在缓存外，但它内部用已缓存的 `existingTables()`，开销小。

### 5.3 改动点

| 文件 | 改动 |
|---|---|
| `db-mcp/tools/SchemaCache.java`（新） | 极简 TTL 缓存，`@Component` |
| `db-mcp/config/DbProperties.java` | record 加 `schemaCacheTtlSeconds` |
| `db-mcp/resources/application.yml` | `app.db` 下加 `schema-cache-ttl-seconds: 600` |
| `db-mcp/tools/DbTools.java` | 构造器注入 `SchemaCache`；`listTables`/`describeTable`/`existingTables` 拆分走缓存 |

### 5.4 取舍
- **不缓存 `sample_data`**：样本数据会变，需实时。
- **缓存共享可变对象**：`existingTables()` 返回 `TreeSet`，缓存命中返回同一实例；现有调用方只读（`contains`/遍历），无修改风险。
- **关系召回增强（`describe_relations` 工具）未做**：其价值取决于账单库外键覆盖度，需先核实 `dst_db_bill` 外键普及度后再定；当前 `describe_table` 已返回外键，LLM 可自行拼接。

---

## 6. 方案三：意图守卫 + 重试契约

### 6.1 DataAgent 对应思想
DataAgent 用独立的 `IntentRecognitionNode` + `FeasibilityAssessmentNode` 判断意图与可行性，因为它后面接复杂多步图，意图决定图路由。**`agent-app` 没有图**——主 LLM 本身就能决定要不要调工具。所以独立意图分类器（每次多一次 LLM 调用，延迟/成本翻倍）对单库场景性价比低。

### 6.2 设计
以 **SYSTEM_PROMPT 强化** 落地这个思想，而非加独立节点。在 `SYSTEM_PROMPT` 追加「意图与边界」段：
1. 与账单数据无关的闲聊/泛问 → 礼貌说明职责（只能分析账单数据库），不调任何工具；
2. 需求模糊或有歧义（缺时间范围/对象/口径）→ 先向用户澄清，不猜测后强行查；
3. 超出只读 SELECT 能力（预测未来/写操作/跨系统）→ 说明边界，不尝试。

同时追加「SQL 执行失败处理」段（方案1 的 prompt 侧）：返回 error 时修正重试、上限 3 次、失败如实告知、严禁编造。

### 6.3 改动点

| 文件 | 改动 |
|---|---|
| `agent-app/config/ChatClientConfig.java` `SYSTEM_PROMPT` | 追加「SQL 执行失败的处理」+「意图与边界」两段 |

### 6.4 取舍
仅当线上观察到大量「闲聊/泛问」流量且 prompt 强化压不住时，才考虑加一道**规则前置**（关键词/正则识别明显非查询，跳过 LLM 直拒）——比加 LLM 分类器更便宜更可控，但仍偏脆，作为观察后演进项。

---

## 7. 不做项与边界（YAGNI）

| 不做 | 原因 |
|---|---|
| 引入 Spring AI Alibaba Graph / StateGraph | 单库只读场景过度设计；轻量增强已覆盖核心短板 |
| 关系召回增强工具 `describe_relations` | 价值取决于账单库外键覆盖度，需先核实 |
| 方案1 受控循环（1b） | 改动大、放弃 LLM 自主性；轻量增强先行，观察后再定 |
| 方案3 独立意图分类器 / 硬规则前置 | 单库场景性价比低；prompt 强化先行 |

---

## 8. 测试策略与验证结果

### 8.1 单元测试（新增）
本项目此前零单元测试。本次为 db-mcp-server 补两块覆盖**本次改动**的核心逻辑：

| 测试类 | 覆盖 | 用例数 |
|---|---|---|
| `SchemaCacheTest` | TTL 命中 / 过期重新加载 / 关闭(ttl≤0 直通) / 不同 key 隔离 | 5 |
| `DbToolsTest` | `runReadonlySql` 对写操作/多语句/空 SQL 的 guard 拒绝 → 返回结构化 error（不连库） | 3 |

`DbToolsTest` 覆盖方案1 的核心路径：守卫拒绝发生在 `executeCapped`（连库）之前，故无需真实数据库；`schemaCacheTtlSeconds=0` 关闭缓存。

### 8.2 验证结果

| 项 | 结果 |
|---|---|
| 编译（`db-mcp-server` + `agent-app`） | ✅ `BUILD SUCCESS`（JDK 21 + Maven 3.9.16） |
| 单元测试 | ✅ **8/8 全绿**（`SchemaCacheTest` 5、`DbToolsTest` 3） |
| 运行时启动 | ✅ 8080 正常启动：MCP db-server 子进程 + 记忆库 HikariPool + Tomcat 全部就绪（改动不影响启动） |
| 运行时端到端（LLM 行为） | 待验证：重试≤3 次、缓存命中、闲聊拒绝（需内网连通 + LLM 调用） |

### 8.3 端到端自验清单（服务在 8080 运行时）
1. **方案3（意图守卫）**：发「你好/今天天气」→ 应不查库、说明只能分析账单库。
2. **方案2（缓存）**：连问两个需 `list_tables` 的问题 → db-mcp 日志第二次应无 `list_tables(查库)`（命中缓存）；`AI_LOG_LEVEL=DEBUG` 可在 agent-app 日志看到 db-mcp 的 stderr。
3. **方案1（错误回灌）**：发写操作请求（如「删掉某表数据」）→ LLM 应被守卫 `error` 劝退/拒绝；或问会触发 SQL 语法错的问题 → 看 LLM 是否基于 error 修正重试 ≤3 次。

---

## 9. 风险与后续演进

- **契约联动**：`QueryResult` 加字段，db-mcp 与 agent-app 需一起重新构建并重启（MCP 序列化兼容，但建议同步重启）。注意 IDEA auto-rebuild 可能覆盖 db-mcp fat jar（参考既有 `-fat.jar` 处理）。
- **缓存生效延迟**：库结构变更需等 TTL（默认 10 分钟）过期；紧急时重启 db-mcp 或置 `schema-cache-ttl-seconds: 0`。
- **后续演进**：
  - 核实账单库外键覆盖度 → 决定是否做 `describe_relations` 关系召回增强；
  - 线上观察 LLM 重试收敛性 → 决定是否升级为方案1 受控循环；
  - 线上观察闲聊流量占比 → 决定是否加规则前置意图守卫；
  - 方案1/3 共用 `SYSTEM_PROMPT`，后续若需 prompt 可配置化，可借鉴 DataAgent 的 `user_prompt_config` 表方案。

---

## 10. 改动文件清单

**db-mcp-server**
- `src/main/java/com/dstcar/nlsql/dbmcp/model/QueryResult.java`（改：加 `error` 字段）
- `src/main/java/com/dstcar/nlsql/dbmcp/tools/DbTools.java`（改：错误结构化回灌 + 接入缓存）
- `src/main/java/com/dstcar/nlsql/dbmcp/tools/SchemaCache.java`（新）
- `src/main/java/com/dstcar/nlsql/dbmcp/config/DbProperties.java`（改：加 `schemaCacheTtlSeconds`）
- `src/main/resources/application.yml`（改：加 `schema-cache-ttl-seconds`）
- `src/test/java/com/dstcar/nlsql/dbmcp/tools/SchemaCacheTest.java`（新）
- `src/test/java/com/dstcar/nlsql/dbmcp/tools/DbToolsTest.java`（新）

**agent-app**
- `src/main/java/com/dstcar/nlsql/agent/config/ChatClientConfig.java`（改：`SYSTEM_PROMPT` 增补两段）

**文档**
- `docs/design-nl2sql-enhancement.md`（新，本文档）
