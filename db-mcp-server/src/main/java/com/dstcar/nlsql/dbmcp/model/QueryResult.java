package com.dstcar.nlsql.dbmcp.model;

import java.util.List;

/** run_readonly_sql / sample_data 的通用返回结构。 */
public record QueryResult(
        List<String> columns,
        List<List<Object>> rows,
        int rowCount,
        boolean truncated,
        String note
) {
}
