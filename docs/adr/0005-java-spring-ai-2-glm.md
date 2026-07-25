# 技术栈 Java + Spring AI 2.0,LLM 用 GLM

框架可选 Python 栈 / LangChain4j / Spring AI,LLM 可选 GLM / DeepSeek / Claude 等。我们选 **Java + Spring AI 2.0(Spring Boot 4.1)**,LLM 用**智谱 GLM**。

注意:Spring AI 2.0 已**移除 ZhipuAI 专用 starter**(1.0.x 有,2.0.0 无)。GLM 改经 `spring-ai-starter-model-openai` 接入,指向智谱的 **OpenAI 兼容端点**(`base-url=https://open.bigmodel.cn/api/paas/v4/`,模型名 `glm-4.6`)—— 这是 2.0 用 GLM 的标准做法。

理由:Spring AI 的 MCP 客户端/服务端皆为一等公民,与现有 Spring Boot 企业栈(Sa-Token / RocketMQ / xxl-job)契合;GLM 中文与 function-calling(OpenAI 兼容格式)够用、国内直连。换 LLM 在 Spring AI 里成本低(只改 base-url / 模型名),故 GLM 选择不单列 ADR。
