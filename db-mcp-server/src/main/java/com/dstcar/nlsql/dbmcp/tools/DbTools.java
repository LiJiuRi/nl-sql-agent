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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 暴露给 Agent 的 4 个只读 MCP 工具(由 Spring AI 注解扫描器自动注册为 MCP 工具)。
 * 所有查询走只读连接(PRAGMA query_only = ON),run_readonly_sql 另加解析层守卫。
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
        List<TableInfo> out = new ArrayList<>();
        for (String t : existingTables()) {
            out.add(new TableInfo(t, SchemaComments.table(t), approxRowCount(t)));
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
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA table_info(\"" + t + "\")")) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    columns.add(new ColumnInfo(
                            name,
                            rs.getString("type"),
                            rs.getInt("notnull") == 0,
                            rs.getInt("pk") > 0,
                            SchemaComments.column(t, name)));
                }
            }
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_key_list(\"" + t + "\")")) {
                while (rs.next()) {
                    fks.add(new ForeignKey(
                            rs.getString("from"),
                            rs.getString("table"),
                            rs.getString("to")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取表结构失败: " + e.getMessage(), e);
        }
        TableSchema schema = new TableSchema(t, SchemaComments.table(t), columns, fks);
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
        QueryResult result = executeCapped("SELECT * FROM \"" + t + "\" LIMIT " + n);
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
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + props.path());
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA query_only = ON"); // 连接级只读:拒绝一切写/DDL
            st.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }

    private String requireTable(String table) {
        String t = table == null ? "" : table.trim();
        if (!existingTables().contains(t)) {
            throw new IllegalArgumentException("表不存在: " + table + "。请先用 list_tables 查看可用表。");
        }
        return t;
    }

    private Set<String> existingTables() {
        Set<String> tables = new TreeSet<>();
        try (Connection c = openReadonlyConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString("name"));
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
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            return -1;
        }
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
