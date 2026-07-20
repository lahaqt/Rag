package com.example.ragagent.memory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class MemoryContentSafety {
    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            Pattern.compile("(?i)(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|passwd|secret)\\s*[:=]\\s*\\S+"),
            Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/=-]{12,}"),
            Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b"),
            Pattern.compile("\\b\\d{15,18}[0-9Xx]\\b")
    );

    private MemoryContentSafety() {
    }

    static boolean isSafe(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String normalized = content.trim().toLowerCase(Locale.ROOT);
        return SENSITIVE_PATTERNS.stream().noneMatch(pattern -> pattern.matcher(normalized).find());
    }
}
