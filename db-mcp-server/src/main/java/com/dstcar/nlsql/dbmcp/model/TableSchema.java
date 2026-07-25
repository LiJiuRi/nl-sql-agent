package com.dstcar.nlsql.dbmcp.model;

import java.util.List;

/** describe_table 的返回结构。 */
public record TableSchema(
        String table,
        String comment,
        List<ColumnInfo> columns,
        List<ForeignKey> foreignKeys
) {
}
