package com.dstcar.nlsql.dbmcp.model;

/** describe_table 中的列描述。 */
public record ColumnInfo(
        String name,
        String type,
        boolean nullable,
        boolean primaryKey,
        String comment
) {
}
