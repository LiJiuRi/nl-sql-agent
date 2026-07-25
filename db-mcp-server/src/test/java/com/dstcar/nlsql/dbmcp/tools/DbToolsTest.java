package com.dstcar.nlsql.dbmcp.tools;

import com.dstcar.nlsql.dbmcp.config.DbProperties;
import com.dstcar.nlsql.dbmcp.db.DatabaseInitializer;
import com.dstcar.nlsql.dbmcp.model.QueryResult;
import com.dstcar.nlsql.dbmcp.model.TableInfo;
import com.dstcar.nlsql.dbmcp.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 直接验证 4 个工具的逻辑 + 只读守卫(绕开 stdio 传输,排除管道竞态干扰)。
 * 每个用例用独立临时库,由 DatabaseInitializer 现场建表 + 播种。
 */
class DbToolsTest {

    private DbTools freshTools(Path dir, String fileName) {
        DbProperties props = new DbProperties(dir.resolve(fileName).toString(), 1000, 10, 1048576L);
        new DatabaseInitializer(props).run(new DefaultApplicationArguments());
        return new DbTools(props);
    }

    @Test
    void listDescribeSample_work(@TempDir Path dir) {
        DbTools tools = freshTools(dir, "t1.db");
        List<TableInfo> tables = tools.listTables();
        assertThat(tables).extracting(TableInfo::name)
                .containsExactlyInAnyOrder("merchant", "bill", "payment", "refund");

        TableSchema bill = tools.describeTable("bill");
        assertThat(bill.columns()).isNotEmpty();
        assertThat(bill.foreignKeys()).isNotEmpty(); // merchant_id → merchant.id

        QueryResult sample = tools.sampleData("bill", 3);
        assertThat(sample.rowCount()).isLessThanOrEqualTo(3);
        assertThat(sample.columns()).contains("id", "merchant_id", "amount", "bill_month", "status");
    }

    @Test
    void runReadonlySql_returnsAggregatedRows(@TempDir Path dir) {
        DbTools tools = freshTools(dir, "t2.db");
        QueryResult r = tools.runReadonlySql(
                "SELECT bill_month, COUNT(*) AS cnt, ROUND(SUM(amount),2) AS total "
                        + "FROM bill GROUP BY bill_month ORDER BY bill_month LIMIT 5");
        assertThat(r.rowCount()).isEqualTo(5);
        assertThat(r.columns()).contains("bill_month", "cnt", "total");
        assertThat(r.truncated()).isFalse();
    }

    @Test
    void guard_rejectsWritesAndDangerousStatements(@TempDir Path dir) {
        DbTools tools = freshTools(dir, "t3.db");
        assertThatThrownBy(() -> tools.runReadonlySql("DELETE FROM bill"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.runReadonlySql(
                "INSERT INTO merchant(name,category,region,created_at) VALUES('x','y','z','2025')"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.runReadonlySql("SELECT * FROM bill; DROP TABLE bill"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.runReadonlySql("PRAGMA database_list"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void guard_allowsWithSelect(@TempDir Path dir) {
        DbTools tools = freshTools(dir, "t4.db");
        QueryResult r = tools.runReadonlySql(
                "WITH m AS (SELECT id, name FROM merchant) SELECT * FROM m LIMIT 2");
        assertThat(r.rowCount()).isLessThanOrEqualTo(2);
        assertThat(r.columns()).contains("id", "name");
    }
}
