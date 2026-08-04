package com.dstcar.nlsql.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 ChatClient:系统提示词(4 段回答 + 反幻觉)+ MCP 工具(db-server 提供)+ 轻量多轮记忆。
 * MCP 客户端 auto-config 会提供一个 ToolCallbackProvider(MCP 工具),直接注入即可。
 */
@Configuration
public class ChatClientConfig {

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository,
                          @Value("${app.memory.max-messages:10}") int maxMessages) {
        // ChatMemoryRepository 由 JdbcChatMemoryRepository auto-config 提供(ADR-0007,持久化到中心 MySQL)
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(maxMessages)
                .build();
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
                          ToolCallbackProvider mcpTools,
                          ChatMemory chatMemory) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultToolCallbacks(mcpTools.getToolCallbacks()) // MCP 工具(db-server)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /** 系统提示词:定义工作流、4 段回答格式、反幻觉原则。 */
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

            ## SQL 执行失败的处理
            - 当 run_readonly_sql 返回结果中含 error 字段(非空)时,表示执行失败或被安全规则拒绝。你必须阅读 error 文本,修正 SQL 后重新调用 run_readonly_sql。
            - 重试上限 3 次。若 3 次后仍失败,如实告知用户失败原因和最后尝试的那条 SQL,严禁编造结果或数字。

            ## 意图与边界(在生成 SQL 之前判断)
            - 与账单数据无关的闲聊或泛问:礼貌说明你的职责(只能分析账单数据库),不要调用任何工具。
            - 需求模糊或有歧义(缺少时间范围/对象/统计口径等):先向用户澄清,不要靠猜测后强行查询。
            - 超出只读 SELECT 能力的请求(如预测未来、写操作、跨系统数据):说明边界,不要尝试。
            """;
}
