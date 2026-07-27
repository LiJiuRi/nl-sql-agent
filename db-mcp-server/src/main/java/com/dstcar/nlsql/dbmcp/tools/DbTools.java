package com.dstcar.nlsql.dbmcp.tools;

import com.dstcar.nlsql.dbmcp.config.DbProperties;
import com.dstcar.nlsql.dbmcp.db.SchemaComments;
import com.dstcar.nlsql.dbmcp.guard.SqlSafetyGuard;
import com.dstcar.nlsql.dbmcp.model.ColumnInfo;
import com.dstcar.nlsql.dbmcp.model.ForeignKey;
import com.dstcar.nlsql.dbmcp.model.QueryResult;
import com.dstcar.nlsql.dbmcp.model.TableInfo;
import com.dstcar.nlsql.dbmcp.model.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 暴露给 Agent 的 4 个只读 MCP 工具(由 Spring AI 注解扫描器自动注册为 MCP 工具)。
 * 所有查询走只读账号(GRANT SELECT),run_readonly_sql 另加解析层守卫。
 */
@Component
public class DbTools {

    private static final Logger log = LoggerFactory.getLogger(DbTools.class);

    private final DbProperties props;
    private final SqlSafetyGuard guard = new SqlSafetyGuard();

    public DbTools(DbProperties props) {
        this.props = props;
    }

    @Tool(name = "list_tables",
            description = "列出账单数据库中所有用户表,含中文注释和近似行数。无参数。")
    public List<TableInfo> listTables() {
        long start = System.nanoTime();
        log.info("[tool] list_tables 调用");
        Set<String> tables = existingTables();
        // 识别分表组:表名形如 <前缀>_<纯数字>,同前缀多张即一组
        Map<String, List<String>> shardGroups = new HashMap<>();
        for (String t : tables) {
            String p = shardPrefix(t);
            if (p != null) {
                shardGroups.computeIfAbsent(p, k -> new ArrayList<>()).add(t);
            }
        }
        List<TableInfo> out = new ArrayList<>();
        for (String t : tables) {
            String comment = tableComment(t);
            String p = shardPrefix(t);
            if (p != null && shardGroups.get(p).size() > 1) {
                String siblings = String.join("/", shardGroups.get(p));
                comment = (comment.isEmpty() ? "" : comment + " ")
                        + "【分表,同组: " + siblings + ",查全量需对同组各分表 UNION ALL】";
            }
            out.add(new TableInfo(t, comment, approxRowCount(t)));
        }
        log.info("[tool] list_tables 完成 表数={} 耗时={}ms", out.size(), (System.nanoTime() - start) / 1_000_000);
        return out;
    }

    @Tool(name = "describe_table",
            description = "描述指定表:列(名/类型/可空/主键/中文注释)与外键关系。用于正确编写 JOIN。"
                    + "参数: table 表名(可用 list_tables 查看)。")
    public TableSchema describeTable(String table) {
        long start = System.nanoTime();
        log.info("[tool] describe_table table={}", table);
        String t = requireTable(table);
        List<ColumnInfo> columns = new ArrayList<>();
        List<ForeignKey> fks = new ArrayList<>();
        try (Connection c = openReadonlyConnection();
             PreparedStatement cols = c.prepareStatement(
                     "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_COMMENT "
                     + "FROM INFORMATION_SCHEMA.COLUMNS "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                     + "ORDER BY ORDINAL_POSITION")) {
            cols.setString(1, t);
            try (ResultSet rs = cols.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    String comment = rs.getString("COLUMN_COMMENT");
                    String fallback = SchemaComments.column(t, name);
                    columns.add(new ColumnInfo(
                            name,
                            rs.getString("DATA_TYPE"),
                            "YES".equals(rs.getString("IS_NULLABLE")),
                            "PRI".equals(rs.getString("COLUMN_KEY")),
                            (comment == null || comment.isEmpty()) ? fallback : comment));
                }
            }
            try (PreparedStatement fksPs = c.prepareStatement(
                    "SELECT COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME "
                    + "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND REFERENCED_TABLE_NAME IS NOT NULL")) {
                fksPs.setString(1, t);
                try (ResultSet rs = fksPs.executeQuery()) {
                    while (rs.next()) {
                        fks.add(new ForeignKey(
                                rs.getString("COLUMN_NAME"),
                                rs.getString("REFERENCED_TABLE_NAME"),
                                rs.getString("REFERENCED_COLUMN_NAME")));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取表结构失败: " + e.getMessage(), e);
        }
        TableSchema schema = new TableSchema(t, tableComment(t), columns, fks);
        log.info("[tool] describe_table 完成 table={} 列数={} 耗时={}ms",
                t, columns.size(), (System.nanoTime() - start) / 1_000_000);
        return schema;
    }

    @Tool(name = "sample_data",
            description = "返回指定表的前若干行真实样本数据,帮助把中文需求映射到字段取值"
                    + "(尤其 status/channel 等枚举值)。参数: table 表名, limit 行数(默认5,上限20)。")
    public QueryResult sampleData(String table, int limit) {
        long start = System.nanoTime();
        log.info("[tool] sample_data table={} limit={}", table, limit);
        String t = requireTable(table);
        int n = (limit <= 0) ? 5 : Math.min(limit, 20);
        QueryResult result = executeCapped("SELECT * FROM `" + t + "` LIMIT " + n);
        log.info("[tool] sample_data 完成 table={} 返回行数={} 截断={} 耗时={}ms",
                t, result.rowCount(), result.truncated(), (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    @Tool(name = "run_readonly_sql",
            description = "执行【单条只读 SELECT】并返回结果(列+行),受行数/超时/体积上限保护;严禁写操作,"
                    + "只支持 SELECT / WITH ... SELECT。参数: sql 要执行的 SQL。")
    public QueryResult runReadonlySql(String sql) {
        long start = System.nanoTime();
        log.info("[tool] run_readonly_sql sql=\"{}\"", summarize(sql));
        try {
            guard.validate(sql);
        } catch (IllegalArgumentException e) {
            log.warn("[guard] 拒绝 原因={} sql=\"{}\"", e.getMessage(), summarize(sql));
            throw e;
        }
        QueryResult result = executeCapped(sql);
        log.info("[tool] run_readonly_sql 完成 返回行数={} 截断={} 耗时={}ms",
                result.rowCount(), result.truncated(), (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    // ---- 内部实现 ----------------------------------------------------------

    private QueryResult executeCapped(String sql) {
        try (Connection c = openReadonlyConnection();
             Statement st = c.createStatement()) {
            st.setQueryTimeout(props.queryTimeoutSeconds());
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int colCount = md.getColumnCount();
                List<String> cols = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    cols.add(md.getColumnLabel(i));
                }
                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                long bytes = 0;
                while (rs.next()) {
                    if (rows.size() >= props.rowLimit()) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = new ArrayList<>(colCount);
                    long rowBytes = 0;
                    for (int i = 1; i <= colCount; i++) {
                        Object v = rs.getObject(i);
                        row.add(v);
                        rowBytes += sizeOf(v);
                    }
                    if (bytes + rowBytes > props.maxBytes()) {
                        truncated = true;
                        break;
                    }
                    bytes += rowBytes;
                    rows.add(row);
                }
                String note = truncated
                        ? "结果已达上限(行数<=" + props.rowLimit() + " 或体积<=" + (props.maxBytes() / 1024)
                        + "KB),已截断。如需精确,请缩小查询范围或加 LIMIT。"
                        : null;
                return new QueryResult(cols, rows, rows.size(), truncated, note);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("执行 SQL 失败: " + e.getMessage(), e);
        }
    }

    private Connection openReadonlyConnection() throws SQLException {
        // 只读由账号权限保证(bill_query_account 只授 SELECT,见 ADR-0006);不依赖 PRAGMA
        return DriverManager.getConnection(props.url(), props.user(), props.password());
    }

    private String requireTable(String table) {
        String t = table == null ? "" : table.trim();
        if (!existingTables().contains(t)) {
            throw new IllegalArgumentException("表不存在: " + table + "。请先用 list_tables 查看可用表。");
        }
        return t;
    }

    private Set<String> existingTables() {
        Set<String> tables = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (Connection c = openReadonlyConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' "
                     + "AND TABLE_NAME NOT REGEXP '(_(bak[0-9]*|copy[0-9]*|syy|delete_bak|temp)$|^(temp|code)_)' "
                     + "AND TABLE_NAME NOT IN ('temp_test','code_temp','bill_test')")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取表清单失败: " + e.getMessage(), e);
        }
        return tables;
    }

    private long approxRowCount(String table) {
        try (Connection c = openReadonlyConnection();
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            return -1;
        }
    }

    /** 表注释:优先取 MySQL TABLE_COMMENT,空则回退 SchemaComments 静态映射。 */
    private String tableComment(String table) {
        try (Connection c = openReadonlyConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT TABLE_COMMENT FROM INFORMATION_SCHEMA.TABLES "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String comment = rs.getString("TABLE_COMMENT");
                    if (comment != null && !comment.isEmpty()) return comment;
                }
            }
        } catch (SQLException ignored) {
            // 落到静态映射 fallback
        }
        return SchemaComments.table(table);
    }

    /** 若表名为 <前缀>_<纯数字> 形式,返回前缀;否则返回 null(非分表)。 */
    private static String shardPrefix(String table) {
        int li = table.lastIndexOf('_');
        if (li <= 0 || li == table.length() - 1) return null;
        String suffix = table.substring(li + 1);
        if (suffix.isEmpty()) return null;
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) return null;
        }
        return table.substring(0, li);
    }

    private static long sizeOf(Object v) {
        if (v == null) return 8;
        if (v instanceof String s) return 16L + s.length() * 2L;
        return 16;
    }

    /** 长文本摘要:截断到 200 字符,避免日志噪声。 */
    private static String summarize(String text) {
        if (text == null) return "";
        return text.length() <= 200 ? text : text.substring(0, 200) + "...(" + text.length() + "字)";
    }
}
