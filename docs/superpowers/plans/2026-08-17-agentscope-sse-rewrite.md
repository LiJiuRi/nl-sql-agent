# AgentScope Java 2.0 重写 + SSE 流式输出 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** agent-app 从 Spring AI 2.0 重写为 AgentScope Java 2.0.0 GA,聊天接口改 SSE 流式(文本增量 + 工具状态),会话上下文持久化到 MySQL。

**Architecture:** 单例无状态 `ReActAgent`(GLM via OpenAIChatModel + GLMFormatter;MCP stdio 子进程接 db-mcp-server;`MysqlAgentStateStore` 按 (userId, conversationId) 持久化)。MVC 控制器把 `streamEvents` 的 `Flux<AgentEvent>` 投影为 4 种精简 SSE 事件。设计文档:`docs/superpowers/specs/2026-08-17-agentscope-2.0-rewrite-design.md`(ADR-0008)。

**Tech Stack:** Spring Boot 4.1 (MVC) · `io.agentscope:agentscope-core:2.0.0` + `agentscope-extensions-model-openai:2.0.0` + `agentscope-extensions-mysql:2.0.0` · Reactor(传递依赖)· MySQL。

## Global Constraints

- 构建必须 `JAVA_HOME=/d/developTools/jdk-21.0.12`(mvn 默认跑 JDK8 会失败;见 memory)
- Maven 本地仓库在 `D:\developTools\maven_repository`(三个 agentscope jar 已拉取)
- 不改 `db-mcp-server` 模块;`auth/`、`conversation/` 包不动
- 表名前缀 `invoice_agent_`;库名 `dst_db_invoice`(ADR-0007)
- 系统提示词的 4 段回答格式与反幻觉约束**原文保留**,仅工具名改为 `mcp__db-server__*`
- 已核实的 API(勿凭文档猜,文档与 GA jar 有出入):
  - `streamEvents(java.util.List<Msg>, RuntimeContext)` → `Flux<AgentEvent>`(GA 有 ctx 重载)
  - `McpClientBuilder.create(String name).stdioTransport(String cmd, List<String> args, Map<String,String> env).buildSync()` → `McpClientWrapper`;`toolkit.registerMcpClient(w)` → `Mono<Void>`
  - `OpenAIChatModel.builder().apiKey().baseUrl().modelName().stream(boolean).formatter(new GLMFormatter()).build()`;GLMFormatter 在 `io.agentscope.extensions.model.openai.formatter`(无参构造)
  - `MysqlAgentStateStore(DataSource, String databaseName, String tableName, boolean createIfNotExist)`
  - 事件取值:`TextBlockDeltaEvent.getDelta()`、`ToolCallStartEvent.getToolCallName()`、`ToolResultEndEvent.getState()`(`io.agentscope.core.message.ToolResultState`)、`AgentResultEvent.getResult().getTextContent()`(在 `AgentEndEvent` 之前发出,携带最终 Msg)
  - `ReActAgent.Builder`:`.name().sysPrompt().model(Model).toolkit().stateStore().generateOptions().build()`
- 本仓库无测试基础设施(历史会话均以构建 + 启动冒烟验证),每个 Task 的验证 = 编译/启动/页面操作,不做单测

---

### Task 1: 引入 AgentScope 依赖 + 新增 Agent 装配与 SSE 控制器(与旧代码并存)

**Files:**
- Modify: `agent-app/pom.xml`(加 agentscope 三件套,spring-ai 暂留)
- Modify: `pom.xml`(父 pom 加 `agentscope.version` 属性)
- Create: `agent-app/src/main/java/com/dstcar/nlsql/agent/config/AgentConfig.java`
- Create: `agent-app/src/main/java/com/dstcar/nlsql/agent/chat/ChatStreamEvent.java`
- Create: `agent-app/src/main/java/com/dstcar/nlsql/agent/chat/ChatStreamController.java`

**Interfaces:**
- Consumes: Spring `DataSource`(auto-config)、`ConversationRepository`(已有)
- Produces: `ReActAgent` bean;`POST /api/chat/stream` → `text/event-stream`,事件 JSON:`{type:"start"|"delta"|"tool"|"done"|"error", conversationId?, text?, name?, state?, message?}`

- [ ] **Step 1.1: 父 pom 加版本属性**

`pom.xml`(根)`<properties>` 内、`<spring-ai.version>` 之后加(spring-ai 属性本 Task 先不删):

```xml
        <agentscope.version>2.0.0</agentscope.version>
```

- [ ] **Step 1.2: agent-app pom 加依赖**

`agent-app/pom.xml` `<dependencies>` 内,MCP client 依赖之后加:

```xml
        <!-- AgentScope Java 2.0(ADR-0008:手动装配,不用 starter——Boot 4.1 兼容面零) -->
        <dependency>
            <groupId>io.agentscope</groupId>
            <artifactId>agentscope-core</artifactId>
            <version>${agentscope.version}</version>
        </dependency>
        <dependency>
            <groupId>io.agentscope</groupId>
            <artifactId>agentscope-extensions-model-openai</artifactId>
            <version>${agentscope.version}</version>
        </dependency>
        <dependency>
            <groupId>io.agentscope</groupId>
            <artifactId>agentscope-extensions-mysql</artifactId>
            <version>${agentscope.version}</version>
        </dependency>
```

- [ ] **Step 1.3: 写 AgentConfig.java**

```java
package com.dstcar.nlsql.agent.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.GLMFormatter;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 装配 AgentScope 2.0 ReActAgent(ADR-0008):
 * GLM(OpenAI 兼容栈 + GLMFormatter)+ db-mcp-server(stdio 子进程,工具名 mcp__db-server__*)
 * + MySQL 状态持久化(AgentState 按 (userId, conversationId) 分桶,自动建表)。
 * ReActAgent 无状态:单例并发服务所有会话,同会话串行由框架保证。
 */
@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    /** 系统提示词:与 Spring AI 版语义一致,仅工具名更新为 MCP 实际注册名(mcp__db-server__*)。 */
    static final String SYSTEM_PROMPT = """
            你是"账单分析助手",帮用户用中文分析一个账单中心的数据库。
            数据库的真实表结构未知,必须先用 mcp__db-server__list_tables 工具发现有哪些表,用 mcp__db-server__describe_table 了解列与外键,必要时 mcp__db-server__sample_data 看枚举取值;不要假设表名或列名。

            ## 工作流程(你自主调用工具,按需多次)
            1. 先 mcp__db-server__list_tables 看有哪些表;mcp__db-server__describe_table 了解表结构(列、类型、外键);必要时 mcp__db-server__sample_data 看真实取值(尤其 status、channel、category、region 等枚举)。
            2. 用 mcp__db-server__run_readonly_sql 执行【单条 SELECT】查询。若 SQL 报错或结果不对,根据错误自行修正后重试。
            3. 拿到结果后,按下述格式回答。

            ## 回答格式(严格 4 段,用 markdown)
            **【复述理解】**(仅当需求模糊或有歧义时写;清晰时省略):一句话说明你的理解。

            **【SQL】**:你最终执行的那条 SQL,放在 ```sql 代码块里。

            **【结构化结果】**:客观数据 —— 先给关键汇总(合计/计数/均值/最大/最小/占比等),再用 markdown 表格给出前若干行明细;列名用中文更友好。若工具返回 note 提示截断,必须写明"⚠️ 数据已截断"。

            **【业务解读】**:基于上面【结构化结果】的真实数字解读:先用一句话陈述事实(引用具体数字),再给洞察/趋势/异常。**严禁编造表格里没有的数字或趋势。** 数据单薄或样本不足时,明确写"样本不足,仅供参考"。

            ## 硬约束
            - 只查不改:只生成 SELECT,任何写操作都会被工具拒绝。
            - 反幻觉:你给出的任何数字结论,都必须能在上面的【结构化结果】里找到对应;找不到就不要说。
            - 用户可能接着上一条追问(如"只看华东""换成按月"),请结合上下文理解。
            """;

    @Bean
    ReActAgent agent(DataSource dataSource,
                     @Value("${app.zhipu.api-key}") String apiKey,
                     @Value("${app.zhipu.base-url:https://open.bigmodel.cn/api/coding/paas/v4/}") String baseUrl,
                     @Value("${app.zhipu.model:glm-5.2}") String modelName,
                     @Value("${app.mcp.db-server-jar:./db-mcp-server/target/db-mcp-server-0.1.0-SNAPSHOT.jar}") String dbMcpJar,
                     @Value("${app.state-store.database:dst_db_invoice}") String stateDatabase) {

        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .formatter(new GLMFormatter())
                .build();

        // db-mcp-server 以 stdio 子进程接入;账单库凭证显式转发(不依赖 OS env 继承,与旧版行为一致)
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + (System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        McpClientWrapper dbServer = McpClientBuilder.create("db-server")
                .stdioTransport(javaExe,
                        List.of("-jar", dbMcpJar),
                        Map.of(
                                "MYSQL_BILL_URL", requireEnv("MYSQL_BILL_URL"),
                                "MYSQL_BILL_USER", requireEnv("MYSQL_BILL_USER"),
                                "MYSQL_BILL_PASSWORD", requireEnv("MYSQL_BILL_PASSWORD")))
                .buildSync();
        Toolkit toolkit = new Toolkit();
        toolkit.registerMcpClient(dbServer).block();
        log.info("[agent] MCP db-server 已连接(stdio 子进程),jar={}", dbMcpJar);

        AgentStateStore stateStore = new MysqlAgentStateStore(
                dataSource, stateDatabase, "invoice_agent_agent_state", true);

        ReActAgent agent = ReActAgent.builder()
                .name("invoice-analyst")
                .sysPrompt(SYSTEM_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .stateStore(stateStore)
                .generateOptions(GenerateOptions.builder().temperature(0.2).build())
                .build();
        log.info("[agent] ReActAgent 装配完成 model={} stateStore={}.invoice_agent_agent_state", modelName, stateDatabase);
        return agent;
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name + "(db-mcp-server 子进程必需,见 README)");
        }
        return v;
    }
}
```

- [ ] **Step 1.4: 写 ChatStreamEvent.java**

```java
package com.dstcar.nlsql.agent.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

/** SSE 事件 DTO:AgentScope AgentEvent 的精简前端投影(前端只需这 5 种)。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatStreamEvent(String type, String text, String name, String state, String message,
                              String conversationId) {

    /** 会话已确定(新建会话时前端据此拿到 conversationId)。 */
    static ChatStreamEvent start(String conversationId) {
        return new ChatStreamEvent("start", null, null, null, null, conversationId);
    }

    static ChatStreamEvent delta(String text) {
        return new ChatStreamEvent("delta", text, null, null, null, null);
    }

    static ChatStreamEvent tool(String name, String state) {
        return new ChatStreamEvent("tool", null, name, state, null, null);
    }

    static ChatStreamEvent done() {
        return new ChatStreamEvent("done", null, null, null, null, null);
    }

    static ChatStreamEvent error(String message) {
        return new ChatStreamEvent("error", null, null, null, message, null);
    }
}
```

- [ ] **Step 1.5: 写 ChatStreamController.java**

```java
package com.dstcar.nlsql.agent.chat;

import com.dstcar.nlsql.agent.auth.JwtFilter;
import com.dstcar.nlsql.agent.conversation.ConversationRepository;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.UserMessage;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天流式接口(SSE):POST /api/chat/stream(需 JWT)。
 * AgentScope streamEvents 事件流投影为 5 种前端事件;流正常结束时把本轮
 * user/assistant 全文落 invoice_agent_message(失败不落库,与旧同步接口语义一致)。
 * Agent 上下文由 MysqlAgentStateStore 按 (userId, conversationId) 持久化。
 */
@RestController
@RequestMapping("/api")
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);
    private static final int TITLE_MAX = 30;

    private final ReActAgent agent;
    private final ConversationRepository conversations;

    public ChatStreamController(ReActAgent agent, ConversationRepository conversations) {
        this.agent = agent;
        this.conversations = conversations;
    }

    public record ChatRequest(String conversationId, String message) {
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> stream(@RequestBody ChatRequest request, HttpServletRequest req) {
        String userId = requireUserId(req);
        // 确定会话:无 conversationId 则新建并落库;有则校验归属(防越权访问他人会话)
        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? newConversation(userId, request.message())
                : request.conversationId();
        if (!conversations.owns(conversationId, userId)) {
            log.warn("[chat-stream] 会话不存在或越权 conversationId={} userId={}", conversationId, userId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或不属于当前用户");
        }
        // 首条消息:若标题仍是占位符(如按钮预建的"新会话 …"),用首条消息摘要覆盖
        titleOnFirstMessage(conversationId, request.message());

        log.info("[chat-stream] 收到 userId={} conversationId={} message=\"{}\"",
                userId, conversationId, summarize(request.message()));
        long start = System.nanoTime();
        AtomicReference<Msg> finalMsg = new AtomicReference<>();

        RuntimeContext ctx = RuntimeContext.builder().userId(userId).sessionId(conversationId).build();
        return Flux.concat(
                        Flux.just(sse(ChatStreamEvent.start(conversationId))),
                        agent.streamEvents(List.of(new UserMessage("user", request.message())), ctx)
                                .doOnNext(evt -> {
                                    if (evt instanceof AgentResultEvent r) {
                                        finalMsg.set(r.getResult());
                                    }
                                })
                                // 正常完成才落库(doOnComplete);取消/异常不落,与旧接口语义一致
                                .doOnComplete(() -> {
                                    conversations.appendMessage(conversationId, "user", request.message());
                                    Msg msg = finalMsg.get();
                                    conversations.appendMessage(conversationId, "assistant",
                                            msg == null ? "" : msg.getTextContent());
                                    log.info("[chat-stream] 完成 conversationId={} 耗时={}ms 回答长度={}",
                                            conversationId, (System.nanoTime() - start) / 1_000_000,
                                            msg == null ? 0 : msg.getTextContent().length());
                                })
                                .mapNotNull(ChatStreamController::toEvent))
                .concatWith(Mono.fromSupplier(() -> sse(ChatStreamEvent.done())))
                .onErrorResume(e -> {
                    log.error("[chat-stream] 失败 conversationId={} 耗时={}ms 异常={}: {}",
                            conversationId, (System.nanoTime() - start) / 1_000_000,
                            e.getClass().getSimpleName(), e.getMessage());
                    ChatStreamEvent ev = ChatStreamEvent.error("分析失败:" + e.getMessage());
                    return Flux.just(sse(ev));
                });
    }

    /** AgentEvent → 前端事件;思考块/模型调用等事件不透出(返回 null 被 mapNotNull 过滤)。 */
    private static ChatStreamEvent toEvent(AgentEvent evt) {
        if (evt instanceof TextBlockDeltaEvent d) {
            return ChatStreamEvent.delta(d.getDelta());
        }
        if (evt instanceof ToolCallStartEvent t) {
            log.info("[chat-stream] 工具开始 {}", t.getToolCallName());
            return ChatStreamEvent.tool(shortName(t.getToolCallName()), "start");
        }
        if (evt instanceof ToolResultEndEvent t) {
            boolean ok = t.getState() == ToolResultState.SUCCESS;
            log.info("[chat-stream] 工具结束 {} state={}", t.getToolCallName(), t.getState());
            return ChatStreamEvent.tool(shortName(t.getToolCallName()), ok ? "success" : "error");
        }
        return null;
    }

    /** mcp__db-server__list_tables → list_tables(前端展示短名)。 */
    private static String shortName(String tool) {
        int i = tool.lastIndexOf("__");
        return i >= 0 ? tool.substring(i + 2) : tool;
    }

    private static ServerSentEvent<ChatStreamEvent> sse(ChatStreamEvent e) {
        return ServerSentEvent.builder(e).build();
    }

    private String newConversation(String userId, String firstMessage) {
        String conversationId = UUID.randomUUID().toString();
        String title = summarize(firstMessage);
        if (title.isBlank()) {
            title = "新会话";
        }
        conversations.create(conversationId, userId, title);
        return conversationId;
    }

    /** 首条消息时把占位标题(以"新会话"开头)更新为首条消息摘要,与 newConversation 的标题逻辑保持一致。 */
    private void titleOnFirstMessage(String conversationId, String firstMessage) {
        String current = conversations.titleOf(conversationId);
        if (current != null && current.startsWith("新会话")) {
            String title = summarize(firstMessage);
            if (!title.isBlank()) {
                conversations.rename(conversationId, title);
            }
        }
    }

    private static String requireUserId(HttpServletRequest req) {
        String uid = (String) req.getAttribute(JwtFilter.USER_ID_ATTR);
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return uid;
    }

    /** 文本摘要:截断到 30 字符(用于日志 + 会话标题)。 */
    private static String summarize(String text) {
        if (text == null) return "";
        return text.length() <= TITLE_MAX ? text : text.substring(0, TITLE_MAX) + "...";
    }
}
```

- [ ] **Step 1.6: 编译验证**

```bash
export JAVA_HOME=/d/developTools/jdk-21.0.12
mvn -pl agent-app -am compile -q
```
Expected: BUILD SUCCESS(旧 ChatController 仍在但不受影响;若 agentscope 传递依赖与 Boot 4.1 冲突,在此暴露)

- [ ] **Step 1.7: Commit**

```bash
git add pom.xml agent-app/pom.xml agent-app/src/main/java/com/dstcar/nlsql/agent/config/AgentConfig.java agent-app/src/main/java/com/dstcar/nlsql/agent/chat/
git commit -m "feat: AgentScope 2.0 装配(GLM/MCP stdio/MySQL 状态)+ SSE 流式控制器(与旧接口并存)"
```

---

### Task 2: 移除 Spring AI 与旧代码;配置清理

**Files:**
- Modify: `agent-app/pom.xml`(删 spring-ai 三依赖)
- Modify: `pom.xml`(删 spring-ai.version 属性与 spring-ai-bom)
- Delete: `agent-app/src/main/java/com/dstcar/nlsql/agent/chat/ChatController.java`
- Delete: `agent-app/src/main/java/com/dstcar/nlsql/agent/config/ChatClientConfig.java`
- Delete: `agent-app/src/main/java/com/dstcar/nlsql/agent/memory/JdbcChatMemoryConfig.java`
- Delete: `agent-app/src/main/java/com/dstcar/nlsql/agent/memory/InvoiceAgentMysqlChatMemoryRepositoryDialect.java`
- Modify: `agent-app/src/main/resources/application.yml`(重写,见下)
- Modify: `agent-app/src/main/resources/schema-mysql.sql`(删 chat_memory 表)

**Interfaces:**
- Consumes: Task 1 的 `app.zhipu.*`/`app.mcp.*`/`app.state-store.*` 配置键
- Produces: 仅 `POST /api/chat/stream`;schema 初始化改由 `spring.sql.init` 执行

- [ ] **Step 2.1: 删旧代码与依赖**

删除上述 4 个 Java 文件;`agent-app/pom.xml` 删掉这三个依赖块:
- `org.springframework.ai:spring-ai-starter-model-openai`
- `org.springframework.ai:spring-ai-starter-mcp-client`
- `org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc`

(注释"GLM (智谱)…"、"MCP 客户端…"、"会话记忆持久化…"随依赖一起删;其余依赖不动)

根 `pom.xml`:删 `<spring-ai.version>2.0.0</spring-ai.version>` 行、`<dependencyManagement>` 里整个 spring-ai-bom 块(删除后 dependencyManagement 为空则整个标签一起删)。

- [ ] **Step 2.2: 重写 application.yml**

整文件替换为:

```yaml
# 启动前需设置的环境变量(凭证走 env,不写死):
#   ZHIPU_API_KEY=...                 # 智谱 GLM key(必填;用 Coding Plan 套餐时 base-url 取下方 coding 端点)
#   MEMORY_DB_URL='jdbc:mysql://host:3306/dst_db_invoice?useSSL=false&serverTimezone=Asia/Shanghai'
#       MEMORY_DB_USER=... MEMORY_DB_PASSWORD=...                                    # 记忆库(可写,ADR-0007)
#   MYSQL_BILL_URL='jdbc:mysql://host:3306/dst_db_bill?...' MYSQL_BILL_USER=bill_query_account MYSQL_BILL_PASSWORD=...  # 只读账单库(传给 db-mcp-server 子进程)
#   JWT_SECRET=...                    # 至少 32 字符
#   ADMIN_PW_HASH='$2a$10$...'        # 可选,覆盖默认 admin 密码 hash
spring:
  datasource:
    # 记忆库(中心 MySQL 的 dst_db_invoice 库,可写账号,ADR-0007)——与只读账单库隔离
    # 同时供 ConversationRepository 与 AgentScope MysqlAgentStateStore 使用
    url: ${MEMORY_DB_URL}
    username: ${MEMORY_DB_USER}
    password: ${MEMORY_DB_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
  sql:
    init:
      # 业务表建表(幂等 IF NOT EXISTS);AgentScope 状态表 invoice_agent_agent_state 由 MysqlAgentStateStore 自建
      mode: always
      schema-locations: classpath:schema-mysql.sql
  mvc:
    async:
      request-timeout: 600000   # SSE 异步请求超时 10 分钟(默认 30s 会在长工具调用时掐断流)
  main:
    banner-mode: off

server:
  port: 8080

app:
  zhipu:
    api-key: ${ZHIPU_API_KEY}
    # 用 GLM Coding Plan 套餐 key 时保持 coding 端点;普通 key 改 https://open.bigmodel.cn/api/paas/v4/
    base-url: ${ZHIPU_BASE_URL:https://open.bigmodel.cn/api/coding/paas/v4/}
    model: ${ZHIPU_MODEL:glm-5.2}
  mcp:
    db-server-jar: ${DB_MCP_SERVER_JAR:./db-mcp-server/target/db-mcp-server-0.1.0-SNAPSHOT.jar}
  state-store:
    database: dst_db_invoice
  auth:
    jwt-secret: ${JWT_SECRET:change-this-jwt-secret-need-at-least-32-chars-long-xxxxx}
    jwt-ttl-hours: 24
    users:
      # 预设账号(管理员加人在此);密码 BCrypt hash。默认 admin/admin123(生产务必用 ADMIN_PW_HASH 覆盖)
      - username: admin
        password-hash: ${ADMIN_PW_HASH:$2a$10$nzU3botqd2PhU/oVS.tXO.8WASLIecqOY4EHHzaQRs.W5jcyV/lJm}

logging:
  level:
    # 排障时可调 DEBUG,看 AgentScope 的 LLM 往返与工具调用
    io.agentscope: ${AI_LOG_LEVEL:INFO}
```

- [ ] **Step 2.3: 清理 schema-mysql.sql**

整文件替换为:

```sql
-- 记忆库 schema(存中心 MySQL 的 dst_db_invoice 库,ADR-0007)
-- 由 spring.sql.init(mode=always)在启动时执行(幂等)。
-- 注意:LLM 上下文记忆已改由 AgentScope MysqlAgentStateStore 管理
-- (表 invoice_agent_agent_state,自建,ADR-0008);旧 invoice_agent_chat_memory 废弃不再创建。

-- 多会话元数据(ADR-0007:按 userId 隔离,各自私有)
CREATE TABLE IF NOT EXISTS invoice_agent_conversation (
    conversation_id VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_conv_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 前端历史(读模型,与 AgentScope AgentState 解耦:AgentState 管 LLM 上下文,本表管全量历史展示)
CREATE TABLE IF NOT EXISTS invoice_agent_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(36)  NOT NULL,
    role            VARCHAR(10)  NOT NULL,
    content         TEXT         NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_msg_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] **Step 2.4: 全量构建验证**

```bash
export JAVA_HOME=/d/developTools/jdk-21.0.12
mvn package -q
```
Expected: BUILD SUCCESS,两模块 jar 产出(db-mcp-server 与 agent-app)

- [ ] **Step 2.5: Commit**

```bash
git add -A
git commit -m "refactor: 移除 Spring AI(旧聊天接口/记忆/MCP 客户端),配置切换到 AgentScope"
```

---

### Task 3: 前端 SSE 流式消费

**Files:**
- Modify: `agent-app/src/main/resources/static/index.html`(发消息逻辑 + 工具状态样式)

**Interfaces:**
- Consumes: `POST /api/chat/stream` SSE 事件 `{type:start|delta|tool|done|error,...}`
- Produces: 无(纯前端)

- [ ] **Step 3.1: 加工具状态行样式**

`index.html` `<style>` 块内(`.typing` 规则之后)加:

```css
    .msg .tool-status { font-size: 12px; color: #8b949e; padding: 2px 12px; line-height: 1.6; }
    .msg .tool-status .err { color: #f87171; }
```

- [ ] **Step 3.2: 替换发消息逻辑**

把 `// 发消息` 注释起的整个 `$('chat-form').addEventListener('submit', ...)` 块替换为:

```javascript
    // 发消息(SSE 流式):文本增量实时渲染,工具调用显示状态行
    $('chat-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const message = $('input').value.trim();
        if (!message) return;
        addMsg('user', esc(message));
        $('input').value = '';
        $('send').disabled = true;
        const typing = addMsg('bot', '<span class="typing">正在分析…</span>');
        let acc = '';       // 累积的原始 markdown
        let toolsEl = null; // 工具状态行容器(首个工具事件时创建)
        try {
            const headers = { 'Content-Type': 'application/json' };
            if (token) headers['Authorization'] = 'Bearer ' + token;
            const res = await fetch('/api/chat/stream', {
                method: 'POST', headers,
                body: JSON.stringify({ conversationId, message })
            });
            if (res.status === 401) {
                token = null; localStorage.removeItem('token');
                showLogin();
                throw new Error('未登录或登录已过期');
            }
            if (!res.ok) {
                const txt = await res.text();
                throw new Error('HTTP ' + res.status + (txt ? ': ' + txt : ''));
            }
            const reader = res.body.getReader();
            const decoder = new TextDecoder();
            let buf = '';
            for (;;) {
                const { done, value } = await reader.read();
                if (done) break;
                buf += decoder.decode(value, { stream: true });
                let sep;
                while ((sep = buf.indexOf('\n\n')) >= 0) {
                    const frame = buf.slice(0, sep);
                    buf = buf.slice(sep + 2);
                    const dataLine = frame.split('\n').find(l => l.startsWith('data:'));
                    if (!dataLine) continue;
                    const ev = JSON.parse(dataLine.slice(5));
                    if (ev.type === 'start') {
                        conversationId = ev.conversationId;
                    } else if (ev.type === 'delta') {
                        acc += ev.text || '';
                        typing.innerHTML = renderMarkdown(acc);
                    } else if (ev.type === 'tool') {
                        if (!toolsEl) {
                            toolsEl = document.createElement('div');
                            toolsEl.className = 'tool-status';
                            typing.parentNode.insertBefore(toolsEl, typing);
                        }
                        const line = document.createElement('div');
                        if (ev.state === 'start') line.textContent = '⚙ ' + ev.name + ' …';
                        else if (ev.state === 'success') line.textContent = '✓ ' + ev.name;
                        else line.textContent = '✗ ' + ev.name + ' 失败', line.className = 'err';
                        toolsEl.appendChild(line);
                    } else if (ev.type === 'error') {
                        acc += (acc ? '\n\n' : '') + '> ⚠️ ' + ev.message;
                        typing.innerHTML = renderMarkdown(acc);
                    }
                    messagesEl().scrollTop = messagesEl().scrollHeight;
                }
            }
            if (!acc) acc = '(空响应)';
            typing.innerHTML = renderMarkdown(acc);
            loadConversations(); // 刷新侧栏(新会话/标题)
        } catch (err) {
            typing.innerHTML = '<span style="color:#f87171">请求失败:' + esc(err.message) + '</span>';
        } finally {
            $('send').disabled = false;
            $('input').focus();
        }
    });
```

- [ ] **Step 3.3: 构建验证**

```bash
export JAVA_HOME=/d/developTools/jdk-21.0.12
mvn -pl agent-app package -q -DskipTests
```
Expected: BUILD SUCCESS(index.html 打进 jar;语法问题在 Task 4 页面冒烟暴露)

- [ ] **Step 3.4: Commit**

```bash
git add agent-app/src/main/resources/static/index.html
git commit -m "feat: 前端改 SSE 流式消费(文本增量渲染 + 工具状态行)"
```

---

### Task 4: 端到端冒烟验证

**Files:** 无代码改动(只读验证;发现问题回上游 Task 修)

- [ ] **Step 4.1: 确认环境变量**

与用户确认 OS 环境变量齐备(ZHIPU_API_KEY、MEMORY_DB_URL/USER/PASSWORD、MYSQL_BILL_URL/USER/PASSWORD、JWT_SECRET)。缺则向用户要,不猜。

- [ ] **Step 4.2: 启动应用**

```bash
export JAVA_HOME=/d/developTools/jdk-21.0.12
nohup $JAVA_HOME/bin/java -jar agent-app/target/agent-app-0.1.0-SNAPSHOT.jar > /tmp/agent-app.log 2>&1 &
```
验证(轮询日志到 Started):
- 日志含 `[agent] MCP db-server 已连接` 与 `[agent] ReActAgent 装配完成`
- MySQL 出现 `invoice_agent_agent_state` 表(MysqlAgentStateStore 自建)
- 失败则读日志定位(常见:env 缺失、jar 路径、DB 连接)

- [ ] **Step 4.3: curl 冒烟(登录 + SSE)**

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
curl -N -X POST localhost:8080/api/chat/stream -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"message":"账单表有哪些?"}'
```
Expected: SSE 帧依次出现 `{"type":"start"...}` → 多个 `{"type":"tool"...}`(list_tables/describe_table…) → 连续 `{"type":"delta"...}` → `{"type":"done"}`;内容为 4 段 markdown 格式

- [ ] **Step 4.4: 数据与隔离验证**

- MySQL:`invoice_agent_message` 新增 user+assistant 两行,assistant 内容与 SSE 拼接一致
- 同 conversationId 再问追问("按月统计呢?")→ 回答体现上下文承接(AgentState 生效)
- 用另一 conversationId 或无 token → 上下文不串扰 / 401

- [ ] **Step 4.5: 页面验证**

浏览器 `localhost:8080`:登录 → 新会话提问 → 观察:工具状态行逐条出现、回答逐字流式渲染、侧栏标题刷新;刷新页面 → 历史完整;切换会话 → 互不串扰。

- [ ] **Step 4.6: 收尾**

```bash
# 停掉冒烟进程
kill %1 2>/dev/null || true
```
(发现问题:回对应 Task 修复后重跑本 Task;全部通过才进 Task 5)

---

### Task 5: 文档同步

**Files:**
- Modify: `docs/architecture.md`、`docs/quickstart.md`、`README.md`(凡提及 Spring AI / POST /api/chat / chat_memory 处)

- [ ] **Step 5.1: 更新文档**

`grep -rn "Spring AI\|/api/chat\b\|chat_memory" README.md docs/` 找出所有提及点,逐处更新:
- 框架表述 → "AgentScope Java 2.0(ReActAgent,ADR-0008)"
- 接口表述 → `POST /api/chat/stream`(SSE 流式)
- 记忆表述 → `invoice_agent_agent_state`(AgentScope AgentState)+ `invoice_agent_message`(前端历史);`invoice_agent_chat_memory` 已废弃
- architecture.md 若有组件图/依赖描述,同步为 AgentScope 装配结构

- [ ] **Step 5.2: Commit**

```bash
git add README.md docs/
git commit -m "docs: 同步 AgentScope 2.0 + SSE 架构描述"
```

---

## Self-Review 结论

- **Spec 覆盖**:依赖替换(T1/T2)、Agent 装配(T1)、SSE 控制器(T1)、记忆持久化(T1/T2)、前端流式(T3)、验证方式(设计文档 4 条 → T2.4/T4 全覆盖)、文档(T5)✓
- **占位符**:无 TBD;所有代码完整给出
- **类型一致性**:`ChatStreamEvent` 工厂方法与控制器/前端字段名一致(`type/text/name/state/message/conversationId`);`ReActAgent` bean 类型与控制器注入一致 ✓
- **已知风险**:① 客户端中途断开 → 本轮不落 invoice_agent_message(AgentState 仍存上下文,接受);② 单条工具调用 >10 分钟会触发 SSE 超时(内网工具场景可接受,ponytail: 需要时再加心跳事件);③ agentscope 传递依赖与 Boot 4.1 冲突在 T1.6 编译期暴露
