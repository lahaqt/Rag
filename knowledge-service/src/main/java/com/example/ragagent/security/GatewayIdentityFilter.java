package com.example.ragagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Requires a gateway- or service-signed identity for knowledge APIs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GatewayIdentityFilter extends OncePerRequestFilter {
    private final String signingSecret;

    public GatewayIdentityFilter(@Value("${rag.security.identity-signing-secret:}") String signingSecret) {
        this.signingSecret = signingSecret == null ? "" : signingSecret.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, jakarta.servlet.ServletException {
        if (signingSecret.isBlank()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Gateway identity signing is not configured");
            return;
        }
        String identity = request.getHeader("X-Rag-User-Id");
        String signature = request.getHeader("X-Rag-Identity-Signature");
        if (identity == null || identity.isBlank() || signature == null || !validSignature(identity.trim(), signature)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Valid gateway or service identity is required");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean validSignature(String identity, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = Base64.getUrlEncoder().withoutPadding().encode(mac.doFinal(identity.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return false;
        }
    }
}
