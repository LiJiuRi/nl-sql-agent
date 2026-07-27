# 账单库从 SQLite 演示库改为接入真实 MySQL + 只读账号

ADR-0003 选 SQLite(零基础设施、便于塞示例数据)作为学习阶段的账单库。现 Agent 要投入实际使用,改为**接入已有的真实账单库**(MySQL `dst_db_bill`,账号 `bill_query_account`)。这是对 0003 的修订:0003 的"自建只读 MCP server"保留且强化,但"SQLite 演示库 + 启动建表播种"部分被真实 MySQL 取代——`DatabaseInitializer` 不再建表播种,改启动探活;system prompt 删掉写死的"4 张表",让 GLM 用 `list_tables` 动态发现真实表。

只读三层纵深(0003)在 MySQL 下重新落地:解析层 `SqlSafetyGuard` 保留(补 MySQL 方言写关键字);连接层从 SQLite 专属的 `PRAGMA query_only` 改为**只读 MySQL 账号**(`GRANT SELECT`)——DB 权限侧硬兜底,等价于 PRAGMA 的连接级只读;上限层(行/超时/体积)保留。

**Considered Options**:连接层考虑过 `Connection.setReadOnly(true)`,但 MySQL Connector/J 下它只是 hint 不强制,排除。JDBC URL 明确**不开 `allowMultiQueries`**——它会绕过 `SqlSafetyGuard` 的单条(禁 `;`)防线,对纯 SELECT 用例纯属多余攻击面。

**Status**: accepted。修订 0003 的库选型部分;0003 的"自建只读 MCP server"决策不变。
