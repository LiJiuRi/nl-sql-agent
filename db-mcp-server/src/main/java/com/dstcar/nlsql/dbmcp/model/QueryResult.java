package com.dstcar.nlsql.dbmcp.model;

import java.util.List;

/** run_readonly_sql / sample_data 的通用返回结构。
 *  error 非 null 表示执行失败或被安全规则拒绝(回灌给 LLM 据此纠错重试);为 null 表示成功。 */
public record QueryResult(
        List<String> columns,
        List<List<Object>> rows,
        int rowCount,
        boolean truncated,
        String note,
        String error
) {
}
