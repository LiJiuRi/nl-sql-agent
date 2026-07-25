package com.dstcar.nlsql.dbmcp.model;

/** list_tables 的单表概要。 */
public record TableInfo(
        String name,
        String comment,
        long approxRowCount
) {
}
