package com.dstcar.nlsql.dbmcp.db;

import com.dstcar.nlsql.dbmcp.config.DbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时探活账单库连接并打印实际表清单(ADR-0006:接真实 MySQL,不再建表播种)。
 * 库表由外部提供(dst_db_bill),表结构由 list_tables 工具运行时动态发现。
 */
@Component
public class DatabaseInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final DbProperties props;

    public DatabaseInitializer(DbProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection c = DriverManager.getConnection(props.url(), props.user(), props.password());
             Statement st = c.createStatement()) {
            st.execute("SELECT 1"); // 探活:验证只读账号能连上
            int total = 0;
            List<String> sample = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")) {
                while (rs.next()) {
                    total++;
                    if (sample.size() < 15) sample.add(rs.getString("TABLE_NAME"));
                }
            }
            log.info("[init] 账单库连接成功 user={} 总表数={} 前15=[{}]",
                    props.user(), total, String.join(", ", sample));
        } catch (SQLException e) {
            throw new IllegalStateException("账单库连接失败: " + e.getMessage(), e);
        }
    }
}
