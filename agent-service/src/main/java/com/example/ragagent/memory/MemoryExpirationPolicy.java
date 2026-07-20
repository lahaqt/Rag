package com.example.ragagent.memory;

import java.time.Duration;
import java.time.Instant;

final class MemoryExpirationPolicy {
    private MemoryExpirationPolicy() {
    }

    static Instant expiresAt(MemoryItem item) {
        if (item == null) {
            return null;
        }
        if (MemoryTypes.PREFERENCE.equals(item.type()) && "user".equals(item.scope())) {
            return null;
        }
        long days = switch (item.type()) {
            case MemoryTypes.FACT -> "user".equals(item.scope()) ? 180 : 30;
            case MemoryTypes.GOAL, MemoryTypes.BUSINESS_CONTEXT, MemoryTypes.PREFERENCE -> 30;
            case MemoryTypes.DECISION -> 90;
            default -> 14;
        };
        return item.updatedAt().plus(Duration.ofDays(days));
    }

    static boolean isExpired(MemoryItem item, Instant now) {
        Instant expiresAt = expiresAt(item);
        return expiresAt != null && !expiresAt.isAfter(now == null ? Instant.now() : now);
    }
}
