package com.dstcar.nlsql.dbmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 只读安全边界与 schema 缓存配置。对应 application.yml 的 app.db.* */
@ConfigurationProperties("app.db")
public record DbProperties(
        String url,
        String user,
        String password,
        int rowLimit,
        int queryTimeoutSeconds,
        long maxBytes,
        long schemaCacheTtlSeconds
) {
}
