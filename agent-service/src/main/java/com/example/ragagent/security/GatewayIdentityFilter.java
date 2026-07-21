package com.example.ragagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts a user identity only when a trusted gateway signs it with the configured HMAC secret.
 * Browser callers must reach these routes through that gateway; user IDs supplied in query/body fields
 * are never themselves authentication material.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GatewayIdentityFilter extends OncePerRequestFilter {
    private static final String USER_ID_HEADER = "X-Rag-User-Id";
    private static final String SIGNATURE_HEADER = "X-Rag-Identity-Signature";
    private static final String MCP_PREFIX = "/api/mcp/servers";

    private final String signingSecret;
    private final Set<String> adminUserIds;

    public GatewayIdentityFilter(
            @Value("${rag.security.identity-signing-secret:}") String signingSecret,
            @Value("${rag.security.admin-user-ids:}") String adminUserIds
    ) {
        this.signingSecret = signingSecret == null ? "" : signingSecret.trim();
        this.adminUserIds = Arrays.stream((adminUserIds == null ? "" : adminUserIds).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/approvals")
                || path.startsWith("/api/memories")
                || path.startsWith("/api/conversations")
                || path.startsWith("/api/feedback")
                || path.startsWith("/api/traces")
                || path.startsWith(MCP_PREFIX));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (signingSecret.isBlank()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Gateway identity signing is not configured");
            return;
        }
        String userId = request.getHeader(USER_ID_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (userId == null || userId.isBlank() || signature == null || !validSignature(userId.trim(), signature)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Valid gateway identity is required");
            return;
        }
        if (request.getRequestURI().startsWith(MCP_PREFIX) && !adminUserIds.contains(userId.trim())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "MCP administration requires an admin identity");
            return;
        }
        request.setAttribute(RequestIdentity.USER_ID_ATTRIBUTE, userId.trim());
        filterChain.doFilter(request, response);
    }

    private boolean validSignature(String userId, String suppliedSignature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = Base64.getUrlEncoder().withoutPadding().encode(mac.doFinal(userId.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected, suppliedSignature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            return false;
        }
    }
}
