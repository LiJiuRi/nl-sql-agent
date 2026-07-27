package com.dstcar.nlsql.agent.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 拦截 /api/*(除 /api/login),校验 Authorization: Bearer <jwt>,把 userId 放入请求属性。
 * 静态资源(/、index.html 等)不拦截。userId 供 Controller 经 @RequestAttribute 取用。
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTR = "userId";

    private final JwtService jwtService;

    public JwtFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        // 仅拦截 /api/*;放行静态资源与 /api/login
        if (!path.startsWith("/api/") || path.equals("/api/login")) {
            chain.doFilter(req, resp);
            return;
        }
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        try {
            String userId = jwtService.verify(auth.substring(7));
            req.setAttribute(USER_ID_ATTR, userId);
        } catch (JwtException | IllegalArgumentException e) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(req, resp);
    }
}
