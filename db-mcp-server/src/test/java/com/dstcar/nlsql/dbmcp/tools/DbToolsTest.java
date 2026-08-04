package com.dstcar.nlsql.dbmcp.tools;

import com.dstcar.nlsql.dbmcp.config.DbProperties;
import com.dstcar.nlsql.dbmcp.model.QueryResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 runReadonlySql 的「守卫拒绝 → 结构化 error」路径(方案1)。
 * 守卫拒绝发生在 executeCapped(连库)之前,故无需真实数据库。
 */
class DbToolsTest {

    private static DbTools newTools() {
        // schemaCacheTtlSeconds=0 关闭缓存;url 随意——guard 拒绝路径不连库
        DbProperties props = new DbProperties(
                "jdbc:mysql://localhost:3306/dummy?useSSL=false", "u", "p",
                1000, 10, 1048576L, 0L);
        return new DbTools(props, new SchemaCache());
    }

    @Test
    void writeStatement_guardRejects_returnsStructuredError() {
        QueryResult r = newTools().runReadonlySql("DELETE FROM t WHERE id = 1");
        assertThat(r.error()).isNotNull();
        assertThat(r.error()).contains("安全规则拒绝");
        assertThat(r.rows()).isEmpty();
        assertThat(r.rowCount()).isZero();
    }

    @Test
    void multiStatement_guardRejects_returnsStructuredError() {
        QueryResult r = newTools().runReadonlySql("SELECT 1; SELECT 2");
        assertThat(r.error()).contains("安全规则拒绝");
    }

    @Test
    void blankSql_guardRejects_returnsStructuredError() {
        QueryResult r = newTools().runReadonlySql("   ");
        assertThat(r.error()).contains("安全规则拒绝");
    }
}
