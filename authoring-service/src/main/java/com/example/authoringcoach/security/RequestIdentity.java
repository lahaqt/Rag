package com.example.authoringcoach.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Reads the subject and roles from Spring Security's validated OIDC resource-server context. */
public final class RequestIdentity {
    public static final String USER_ID_ATTRIBUTE = RequestIdentity.class.getName() + ".userId";
    public static final String ADMIN_ATTRIBUTE = RequestIdentity.class.getName() + ".admin";

    private RequestIdentity() {
    }

    public static String requiredUserId(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getName() != null && !authentication.getName().isBlank()) return authentication.getName();
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user identity is required");
    }

    public static String requireMatchingUser(HttpServletRequest request, String requestedUserId) {
        String authenticatedUserId = requiredUserId(request);
        if (requestedUserId == null || !authenticatedUserId.equals(requestedUserId.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requested user does not match the authenticated identity");
        }
        return authenticatedUserId;
    }

    public static boolean isAdmin(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    public static String requireAdmin(HttpServletRequest request) {
        String userId = requiredUserId(request);
        if (!isAdmin(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access is required");
        }
        return userId;
    }
}
