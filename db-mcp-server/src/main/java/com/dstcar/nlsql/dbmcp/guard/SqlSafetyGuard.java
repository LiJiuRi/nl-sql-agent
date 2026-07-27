package com.dstcar.nlsql.dbmcp.guard;

import java.util.Set;

/**
 * 解析层只读守卫:确保进入 run_readonly_sql 的 SQL 只是一条 SELECT。
 * 与只读 MySQL 账号(GRANT SELECT)构成纵深防御(ADR-0003 / 0006)。
 *
 * 规则:
 *  1. 非空、单条语句(禁 ';');
 *  2. 首关键字 ∈ {SELECT, WITH};
 *  3. 全程不得出现任何写/DDL/事务/PRAGMA 关键字(token 级匹配,避免误伤标识符)。
 */
public final class SqlSafetyGuard {

    private static final Set<String> ALLOWED_START = Set.of("SELECT", "WITH");

    private static final Set<String> FORBIDDEN = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "REPLACE",
            "ATTACH", "DETACH", "PRAGMA", "VACUUM", "REINDEX", "GRANT", "REVOKE",
            "TRIGGER", "SAVEPOINT", "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION",
            "EXPLAIN", "ANALYZE", "LOAD", "INTO",
            // MySQL 方言写/DDL/会话/过程关键字
            "CALL", "DO", "SET", "FLUSH", "HANDLER", "LOCK", "UNLOCK",
            "RESET", "SHUTDOWN", "KILL", "USE"
    );

    /** 校验失败抛 IllegalArgumentException(会被工具层转告给 LLM)。 */
    public void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 为空");
        }
        // 去掉末尾分号后,若仍有分号 → 多条语句
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (trimmed.indexOf(';') >= 0) {
            throw new IllegalArgumentException("仅允许单条 SQL(检测到多条语句)");
        }

        String upper = stripLeadingComments(trimmed).toUpperCase();
        String firstToken = firstToken(upper);
        if (firstToken.isEmpty() || !ALLOWED_START.contains(firstToken)) {
            throw new IllegalArgumentException(
                    "仅允许 SELECT / WITH ... SELECT,检测到首关键字: " + firstToken);
        }

        // token 级禁用关键字检查(按非单词字符切分,避免子串误伤)
        for (String token : upper.split("[^A-Z0-9_]+")) {
            if (FORBIDDEN.contains(token)) {
                throw new IllegalArgumentException("禁用关键字: " + token);
            }
        }
    }

    private static String stripLeadingComments(String s) {
        String r = s;
        while (true) {
            r = r.trim();
            if (r.startsWith("--")) {                       // 行注释
                int nl = r.indexOf('\n');
                if (nl < 0) return "";
                r = r.substring(nl + 1);
            } else if (r.startsWith("/*")) {                // 块注释
                int end = r.indexOf("*/");
                if (end < 0) return "";
                r = r.substring(end + 2);
            } else {
                return r;
            }
        }
    }

    private static String firstToken(String upper) {
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return upper.substring(0, i);
            }
        }
        return upper;
    }
}
