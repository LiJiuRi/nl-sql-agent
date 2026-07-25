package com.dstcar.nlsql.dbmcp.db;

import java.util.Map;

/** 表/列的中文注释字典,供 list_tables / describe_table 返回,帮助 LLM 理解领域语义。 */
public final class SchemaComments {

    private SchemaComments() {
    }

    public static final Map<String, String> TABLE = Map.of(
            "merchant", "商户:接入账单中心的商家",
            "bill", "账单:某商户某月的应结算账单(核心实体)",
            "payment", "支付/回款:针对某账单的收款记录",
            "refund", "退款:针对某账单的退款记录"
    );

    public static final Map<String, String> COLUMN = Map.ofEntries(
            Map.entry("merchant.id", "商户ID(主键)"),
            Map.entry("merchant.name", "商户名称"),
            Map.entry("merchant.category", "商户类别:出行 / 能源 / 物流 / 租车"),
            Map.entry("merchant.region", "所在区域:华东 / 华南 / 华北 / 华西"),
            Map.entry("merchant.created_at", "商户创建时间(ISO 文本)"),

            Map.entry("bill.id", "账单ID(主键)"),
            Map.entry("bill.merchant_id", "所属商户ID(外键 → merchant.id)"),
            Map.entry("bill.amount", "账单金额(元,REAL)"),
            Map.entry("bill.bill_month", "账期月份(如 2025-07)"),
            Map.entry("bill.status", "状态:ISSUED 已出 / SETTLED 已结 / OVERDUE 逾期"),
            Map.entry("bill.created_at", "账单创建时间(ISO 文本)"),

            Map.entry("payment.id", "支付ID(主键)"),
            Map.entry("payment.bill_id", "对应账单ID(外键 → bill.id)"),
            Map.entry("payment.amount", "支付金额(元,REAL)"),
            Map.entry("payment.channel", "支付渠道:WECHAT / ALIPAY / BANK"),
            Map.entry("payment.paid_at", "支付时间(ISO 文本)"),

            Map.entry("refund.id", "退款ID(主键)"),
            Map.entry("refund.bill_id", "对应账单ID(外键 → bill.id)"),
            Map.entry("refund.amount", "退款金额(元,REAL)"),
            Map.entry("refund.reason", "退款原因"),
            Map.entry("refund.refunded_at", "退款时间(ISO 文本)")
    );

    public static String table(String t) {
        return TABLE.getOrDefault(t, "");
    }

    public static String column(String table, String col) {
        return COLUMN.getOrDefault(table + "." + col, "");
    }
}
