package com.example.ragagent.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Reads the identity asserted by the authenticated gateway filter. */
public final class RequestIdentity {
    public static final String USER_ID_ATTRIBUTE = RequestIdentity.class.getName() + ".userId";
    public static final String ADMIN_ATTRIBUTE = RequestIdentity.class.getName() + ".admin";

    private RequestIdentity() {
    }

    public static String requiredUserId(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(USER_ID_ATTRIBUTE);
        if (value instanceof String userId && !userId.isBlank()) {
            return userId;
        }
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
        return request != null && Boolean.TRUE.equals(request.getAttribute(ADMIN_ATTRIBUTE));
    }

    public static String requireAdmin(HttpServletRequest request) {
        String userId = requiredUserId(request);
        if (!isAdmin(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access is required");
        }
        return userId;
    }
}
