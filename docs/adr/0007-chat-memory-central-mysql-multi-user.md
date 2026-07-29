# 会话记忆持久化到中心 MySQL;多用户各自私有;轻认证;不做分享

记忆原为 `InMemoryChatMemoryRepository`(重启即失)。需求要求"多会话 + 重启不丢 + 多人/团队共享"。

决定:记忆改用 Spring AI 的 `JdbcChatMemoryRepository`,存中心 MySQL 的 `agent_memory` 库(**可写账号**),与只读账单库物理隔离——账单库是只读的,而记忆要写,账号/库都不能混用。多用户**各自私有**:会话按 userId 隔离,用户看不到别人的会话。认证走**轻量方案**(预设账号 + BCrypt + JWT,不引 Spring Security),内网团队工具够用,userId 可被伪造的残余风险可接受。会话标题用首条消息截断自动生成;记忆窗口 10 不变。agent-app + 记忆库部署到团队服务器。

**Considered Options**:
- **分享功能砍到二期**(YAGNI):"私有 + 可分享"本质是权限系统雏形,一期引入成本远超核心需求价值;先做"各自私有 + 持久化",分享等真有需要再加。
- 排除"记忆做 SQLite 文件 + git 同步以跨终端共享":SQLite 是二进制,git 不能 diff/merge;运行时状态进版本控制是反模式;多端并发对话 git pull/push 二进制冲突会丢数据。跨终端共享运行时数据的正解是中心库,不是 git。

**Consequences**:引入"用户/会话归属"概念;`app_user` 不建表(预设账号在 `application.yml`);新增 `conversation(conversation_id, user_id, title, created_at)` 表与 Spring AI 的 `SPRING_AI_CHAT_MEMORY` 表同在 `agent_memory` 库。Spring AI 2.0 不提供 `schema-mysql.sql`,需用 bundled 的 `schema-mariadb.sql` 改(去 `CREATE INDEX IF NOT EXISTS`)或自备 `schema.sql`。

**Status**: accepted。

**实施注记(2026-07-29)**:本 ADR 的持久化此前**并未真正生效**——`pom` 声明的 `spring-ai-starter-model-chat-memory-repository-jdbc` 本地未拉取、且 `initialize-schema` 默认 `embedded`(MySQL 不执行建表),运行时回退 `InMemoryChatMemoryRepository`,导致 `SPRING_AI_CHAT_MEMORY` 表为"死表"(architecture.md 此前已如实记录"记忆不持久")。本次修复并统一命名:

- `initialize-schema: always` 让建表脚本在 MySQL 上执行;补 `sequence_id BIGINT NOT NULL` 列(2.0.0 的 INSERT/`ORDER BY sequence_id` 依赖它,旧 schema 缺失会导致写库报错)。
- Spring AI 2.0 无 `table-name` 配置项(GitHub issue #2974,最终决定是统一命名而非可配),故新增 `InvoiceAgentMysqlChatMemoryRepositoryDialect`(override 全部 4 条 SQL)把表名固定为 `invoice_agent_chat_memory`,并以 `JdbcChatMemoryConfig` 显式声明 `JdbcChatMemoryRepository` bean 让 dialect 生效。
- 命名统一:记忆库 `agent_memory` → **`dst_db_invoice`**;三表统一 `invoice_agent_` 前缀(`invoice_agent_chat_memory`/`invoice_agent_conversation`/`invoice_agent_message`)。
- Consequences 中"Spring AI 2.0 不提供 `schema-mysql.sql`"系 M/RC 旧版认知,2.0.0 GA 已提供 bundled `schema-mysql.sql`;本项目仍自备 schema 以统一表名。
