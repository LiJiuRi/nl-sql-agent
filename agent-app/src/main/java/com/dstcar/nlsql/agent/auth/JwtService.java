package com.dstcar.nlsql.agent.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/** JWT 签发/校验(HS256,无状态,重启不丢登录态)。 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(AuthProperties props) {
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = Duration.ofHours(Math.max(props.jwtTtlHours(), 1)).toMillis();
    }

    /** 签发 token,subject 为 userId(一期 userId = username)。 */
    public String issue(String userId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key)
                .compact();
    }

    /** 校验 token 并返回 userId;token 非法/过期抛 JwtException。 */
    public String verify(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }
}
