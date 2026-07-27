# 会话记忆持久化到中心 MySQL;多用户各自私有;轻认证;不做分享

记忆原为 `InMemoryChatMemoryRepository`(重启即失)。需求要求"多会话 + 重启不丢 + 多人/团队共享"。

决定:记忆改用 Spring AI 的 `JdbcChatMemoryRepository`,存中心 MySQL 的 `agent_memory` 库(**可写账号**),与只读账单库物理隔离——账单库是只读的,而记忆要写,账号/库都不能混用。多用户**各自私有**:会话按 userId 隔离,用户看不到别人的会话。认证走**轻量方案**(预设账号 + BCrypt + JWT,不引 Spring Security),内网团队工具够用,userId 可被伪造的残余风险可接受。会话标题用首条消息截断自动生成;记忆窗口 10 不变。agent-app + 记忆库部署到团队服务器。

**Considered Options**:
- **分享功能砍到二期**(YAGNI):"私有 + 可分享"本质是权限系统雏形,一期引入成本远超核心需求价值;先做"各自私有 + 持久化",分享等真有需要再加。
- 排除"记忆做 SQLite 文件 + git 同步以跨终端共享":SQLite 是二进制,git 不能 diff/merge;运行时状态进版本控制是反模式;多端并发对话 git pull/push 二进制冲突会丢数据。跨终端共享运行时数据的正解是中心库,不是 git。

**Consequences**:引入"用户/会话归属"概念;`app_user` 不建表(预设账号在 `application.yml`);新增 `conversation(conversation_id, user_id, title, created_at)` 表与 Spring AI 的 `SPRING_AI_CHAT_MEMORY` 表同在 `agent_memory` 库。Spring AI 2.0 不提供 `schema-mysql.sql`,需用 bundled 的 `schema-mariadb.sql` 改(去 `CREATE INDEX IF NOT EXISTS`)或自备 `schema.sql`。

**Status**: accepted。
