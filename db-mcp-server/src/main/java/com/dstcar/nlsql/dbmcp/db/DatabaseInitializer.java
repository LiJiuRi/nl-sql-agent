package com.dstcar.nlsql.dbmcp.db;

import com.dstcar.nlsql.dbmcp.config.DbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Random;

/**
 * 启动时确保账单库存在并已播种示例数据。
 * 仅当库为空时写入;之后由 {@link com.dstcar.nlsql.dbmcp.tools.DbTools} 以只读连接查询。
 */
@Component
public class DatabaseInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private static final String[] CATEGORIES = {"出行", "能源", "物流", "租车"};
    private static final String[] REGIONS = {"华东", "华南", "华北", "华西"};
    private static final String[] CHANNELS = {"WECHAT", "ALIPAY", "BANK"};
    private static final String[] REFUND_REASONS = {"服务投诉", "重复扣款", "订单取消", "协议退款", "价格调整"};
    private static final String[] STATUSES = {"SETTLED", "SETTLED", "SETTLED", "ISSUED", "OVERDUE"};

    private final DbProperties props;

    public DatabaseInitializer(DbProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureParentDir();
            try (Connection c = openWriteConnection()) {
                createSchema(c);
                if (countMerchants(c) == 0) {
                    seed(c);
                    log.info("账单库已播种示例数据 -> {}", props.path());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("初始化账单库失败: " + e.getMessage(), e);
        }
    }

    private void ensureParentDir() {
        Path parent = Path.of(props.path()).getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (Exception e) {
                throw new IllegalStateException("无法创建数据目录: " + parent, e);
            }
        }
    }

    private Connection openWriteConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + props.path());
    }

    private void createSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS merchant (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      name TEXT NOT NULL,
                      category TEXT NOT NULL,
                      region TEXT NOT NULL,
                      created_at TEXT NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS bill (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      merchant_id INTEGER NOT NULL,
                      amount REAL NOT NULL,
                      bill_month TEXT NOT NULL,
                      status TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      FOREIGN KEY (merchant_id) REFERENCES merchant(id)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS payment (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      bill_id INTEGER NOT NULL,
                      amount REAL NOT NULL,
                      channel TEXT NOT NULL,
                      paid_at TEXT NOT NULL,
                      FOREIGN KEY (bill_id) REFERENCES bill(id)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS refund (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      bill_id INTEGER NOT NULL,
                      amount REAL NOT NULL,
                      reason TEXT NOT NULL,
                      refunded_at TEXT NOT NULL,
                      FOREIGN KEY (bill_id) REFERENCES bill(id)
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_bill_month ON bill(bill_month)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_bill_merchant ON bill(merchant_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_payment_bill ON payment(bill_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_refund_bill ON refund(bill_id)");
        }
    }

    private int countMerchants(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM merchant")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** 播种:50 商户 × 12 账期 × 1~3 账单,含支付与退款(确定性随机)。 */
    private void seed(Connection c) throws SQLException {
        Random rnd = new Random(42L); // 固定种子,保证可复现
        long base = 1704067200000L;   // 2024-01-01T00:00:00Z 的毫秒数

        try (PreparedStatement pm = c.prepareStatement(
                "INSERT INTO merchant(name, category, region, created_at) VALUES (?,?,?,?)")) {
            for (int i = 1; i <= 50; i++) {
                pm.setString(1, "商户-" + String.format("%03d", i));
                pm.setString(2, CATEGORIES[rnd.nextInt(CATEGORIES.length)]);
                pm.setString(3, REGIONS[rnd.nextInt(REGIONS.length)]);
                pm.setString(4, iso(base + (long) rnd.nextInt(30) * 86400000L));
                pm.addBatch();
            }
            pm.executeBatch();
        }

        String[] months = {"2025-01", "2025-02", "2025-03", "2025-04", "2025-05", "2025-06",
                "2025-07", "2025-08", "2025-09", "2025-10", "2025-11", "2025-12"};

        try (PreparedStatement pb = c.prepareStatement(
                "INSERT INTO bill(merchant_id, amount, bill_month, status, created_at) VALUES (?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
             PreparedStatement pp = c.prepareStatement(
                "INSERT INTO payment(bill_id, amount, channel, paid_at) VALUES (?,?,?,?)");
             PreparedStatement pr = c.prepareStatement(
                "INSERT INTO refund(bill_id, amount, reason, refunded_at) VALUES (?,?,?,?)")) {

            for (int m = 1; m <= 50; m++) {
                for (String month : months) {
                    int billCount = 1 + rnd.nextInt(3); // 1~3 张/月
                    for (int b = 0; b < billCount; b++) {
                        double amount = 1000 + rnd.nextInt(49000);
                        String status = STATUSES[rnd.nextInt(STATUSES.length)];
                        long createdOffset = monthToMillis(month) + (long) rnd.nextInt(20) * 86400000L;

                        pb.setInt(1, m);
                        pb.setDouble(2, amount);
                        pb.setString(3, month);
                        pb.setString(4, status);
                        pb.setString(5, iso(createdOffset));
                        pb.executeUpdate();

                        long billId;
                        try (ResultSet keys = pb.getGeneratedKeys()) {
                            keys.next();
                            billId = keys.getLong(1);
                        }

                        // SETTLED → 全额支付;ISSUED → 约 30% 部分支付;OVERDUE → 无支付
                        if ("SETTLED".equals(status) || ("ISSUED".equals(status) && rnd.nextInt(10) < 3)) {
                            double paid = "SETTLED".equals(status) ? amount : Math.round(amount * 0.5 * 100) / 100.0;
                            pp.setLong(1, billId);
                            pp.setDouble(2, paid);
                            pp.setString(3, CHANNELS[rnd.nextInt(CHANNELS.length)]);
                            pp.setString(4, iso(createdOffset + (long) rnd.nextInt(10) * 86400000L));
                            pp.addBatch();
                        }

                        // 约 6% 的账单有退款
                        if (rnd.nextInt(100) < 6) {
                            double refund = Math.round(amount * (0.05 + rnd.nextInt(15) / 100.0) * 100) / 100.0;
                            pr.setLong(1, billId);
                            pr.setDouble(2, refund);
                            pr.setString(3, REFUND_REASONS[rnd.nextInt(REFUND_REASONS.length)]);
                            pr.setString(4, iso(createdOffset + (long) rnd.nextInt(15) * 86400000L));
                            pr.addBatch();
                        }
                    }
                }
            }
            pp.executeBatch();
            pr.executeBatch();
        }
    }

    /** 把 "2025-07" 转为该月 1 日 00:00 UTC 的毫秒数。 */
    private static long monthToMillis(String ym) {
        String[] p = ym.split("-");
        int year = Integer.parseInt(p[0]);
        int month = Integer.parseInt(p[1]);
        // 粗略计算(用于示例时间戳,无需精确历法)
        long days = (year - 1970) * 365L + (month - 1) * 30L;
        return days * 86400000L;
    }

    private static String iso(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis).toString();
    }
}
