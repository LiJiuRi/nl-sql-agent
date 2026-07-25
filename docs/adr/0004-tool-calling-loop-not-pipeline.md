# 工具调用循环,而非固定流水线

NL→SQL→执行→解读 可写成固定流水线(取 schema → 生成 SQL → 执行 → 解读),或交给 LLM 自主选工具的 agent 循环。我们用 **Spring AI 自带的工具调用循环**:把 `list_tables` / `describe_table` / `run_readonly_sql` 等 MCP 工具交给 GLM 自主编排。理由:流水线下 MCP 退化成"花哨的函数调用",与"练 MCP + 做 agent"相悖;只有 LLM 自主选工具,agent 和 MCP 才同时成立,且天然支持 SQL 自纠错(执行报错 → 改 SQL 重试)。
