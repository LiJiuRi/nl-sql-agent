package com.dstcar.nlsql.agent.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/** 预设账号校验(BCrypt)。一期 userId = username,无独立用户表(ADR-0007)。 */
@Component
public class UserStore {

    private final Map<String, AuthProperties.UserAccount> byUsername;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserStore(AuthProperties props) {
        this.byUsername = (props.users() == null) ? Map.of()
                : props.users().stream()
                        .collect(Collectors.toMap(AuthProperties.UserAccount::username, u -> u));
    }

    /** 校验成功返回 userId(=username),失败返回 null。 */
    public String authenticate(String username, String password) {
        var u = byUsername.get(username);
        if (u == null || u.passwordHash() == null || u.passwordHash().isBlank()) return null;
        return encoder.matches(password, u.passwordHash()) ? username : null;
    }
}
