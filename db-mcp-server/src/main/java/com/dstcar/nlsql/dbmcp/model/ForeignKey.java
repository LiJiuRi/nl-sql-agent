package com.dstcar.nlsql.dbmcp.model;

/** describe_table 中的外键关系(供 LLM 推理 JOIN)。 */
public record ForeignKey(
        String column,
        String referencesTable,
        String referencesColumn
) {
}
