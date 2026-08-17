package com.dstcar.nlsql.agent.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
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
 * GLM(OpenAI 兼容栈 + GLMFormatter)+ db-mcp-server(stdio 子进程,工具以短名注册:list_tables 等)
 * + MySQL 状态持久化(AgentState 按 (userId, conversationId) 分桶,自动建表)。
 * ReActAgent 无状态:单例并发服务所有会话,同会话串行由框架保证。
 */
@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    /** 系统提示词:与 Spring AI 版原文一致(AgentScope 对 MCP 工具沿用短名注册,无需改动)。 */
    static final String SYSTEM_PROMPT = """
            你是"账单分析助手",帮用户用中文分析一个账单中心的数据库。
            数据库的真实表结构未知,必须先用 list_tables 工具发现有哪些表,用 describe_table 了解列与外键,必要时 sample_data 看枚举取值;不要假设表名或列名。

            ## 工作流程(你自主调用工具,按需多次)
            1. 先 list_tables 看有哪些表;describe_table 了解表结构(列、类型、外键);必要时 sample_data 看真实取值(尤其 status、channel、category、region 等枚举)。
            2. 用 run_readonly_sql 执行【单条 SELECT】查询。若 SQL 报错或结果不对,根据错误自行修正后重试。
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
                // 权限默认 ASK 会令工具调用停在 RequireUserConfirmEvent 等人工审批;
                // 本 agent 仅挂 4 个只读 DB 工具(服务端 SqlSafetyGuard + 只读账号双重兜底),
                // 故 BYPASS 直接放行,真正的安全边界在 db-mcp-server 与数据库账号
                .permissionContext(PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
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
