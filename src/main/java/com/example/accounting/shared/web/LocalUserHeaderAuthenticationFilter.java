package com.example.accounting.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class LocalUserHeaderAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-User-Id";
    private final boolean enabled;
    private final byte[] devBearerToken;
    private final UUID devBearerUserId;
    private final boolean autoLoginEnabled;
    private final UUID autoLoginUserId;

    LocalUserHeaderAuthenticationFilter(boolean enabled) {
        this(enabled, "", "", false);
    }

    LocalUserHeaderAuthenticationFilter(boolean enabled, String devBearerToken, String devBearerUserId) {
        this(enabled, devBearerToken, devBearerUserId, false);
    }

    LocalUserHeaderAuthenticationFilter(boolean enabled, String devBearerToken, String devBearerUserId,
                                        boolean autoLoginEnabled) {
        this(enabled, devBearerToken, devBearerUserId, autoLoginEnabled, devBearerUserId);
    }

    LocalUserHeaderAuthenticationFilter(boolean enabled, String devBearerToken, String devBearerUserId,
                                        boolean autoLoginEnabled, String autoLoginUserId) {
        this.enabled = enabled;
        this.devBearerToken = devBearerToken == null ? new byte[0]
                : devBearerToken.getBytes(StandardCharsets.UTF_8);
        this.devBearerUserId = parseUserId(devBearerUserId);
        this.autoLoginEnabled = autoLoginEnabled;
        this.autoLoginUserId = parseUserId(autoLoginUserId);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String value = enabled ? request.getHeader(HEADER) : null;
        if (enabled && isDevBearerToken(request.getHeader("Authorization"))) {
            value = devBearerUserId == null ? null : devBearerUserId.toString();
        }
        if (enabled && autoLoginEnabled && value == null && autoLoginUserId != null) {
            value = autoLoginUserId.toString();
        }
        if (value == null || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }
        UUID userId;
        try {
            userId = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            filterChain.doFilter(request, response);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                userId.toString(), "n/a", List.of()));
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isDevBearerToken(String authorization) {
        if (devBearerToken.length == 0 || authorization == null
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        byte[] supplied = authorization.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(devBearerToken, supplied);
    }

    private UUID parseUserId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
