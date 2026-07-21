package com.example.ragagent.memory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class MemoryContentSafety {
    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            Pattern.compile("(?i)(?:api[_ -]?key|access[_ -]?key|access[_ -]?token|refresh[_ -]?token|client[_ -]?secret|password|passwd|secret|credential)\\s*(?:[:=]|\\bis\\b)\\s*\\S+"),
            Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/=-]{12,}"),
            Pattern.compile("(?i)https?://[^/@\\s:]+:[^/@\\s]+@"),
            Pattern.compile("(?i)-----begin (?:[a-z ]+ )?private key-----"),
            Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
            Pattern.compile("\\b(?:ghp|gho|github_pat|sk|rk|pk)_[a-zA-Z0-9_-]{16,}\\b"),
            Pattern.compile("\\bxox[baprs]-[a-zA-Z0-9-]{10,}\\b"),
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
