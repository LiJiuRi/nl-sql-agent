package com.dstcar.nlsql.agent.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 轻量认证配置(ADR-0007):JWT + 预设账号(管理员在 application.yml 加人)。 */
@ConfigurationProperties("app.auth")
public record AuthProperties(
        String jwtSecret,
        long jwtTtlHours,
        List<UserAccount> users
) {
    public record UserAccount(String username, String passwordHash) {
    }
}
