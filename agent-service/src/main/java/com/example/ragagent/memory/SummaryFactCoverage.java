package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatMessage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts exact facts that a lossy summary must preserve. */
final class SummaryFactCoverage {
    private static final Pattern EXACT_FACT = Pattern.compile(
            "(?i)(?:https?://\\S+|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|"
                    + "\\b\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\b|"
                    + "\\b[A-Z][A-Z0-9_-]*\\d[A-Z0-9_-]*\\b|"
                    + "\\b\\d+(?:\\.\\d+)?(?:\\s?(?:%|ms|s|分钟|小时|天|元|美元|tokens?))?\\b)"
    );
    private static final Pattern KEY_STATEMENT = Pattern.compile(
            "(?i)(?:\\b(?:prefer(?:s|red)?|must|decision|decided|goal|unresolved|deadline|never|always)\\b"
                    + "[^.!?\\n]{0,120}|"
                    + "(?:偏好|必须|决定|目标|未解决|不要|始终|截止)[^。！？\\n]{0,80})"
    );

    private SummaryFactCoverage() {
    }

    static List<String> extract(String currentSummary, List<ChatMessage> messages) {
        Set<String> facts = new LinkedHashSet<>();
        collect(currentSummary, facts);
        if (messages != null) {
            messages.forEach(message -> collect(message == null ? "" : message.content(), facts));
        }
        return List.copyOf(facts);
    }

    static double coverage(String summary, List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return 1.0;
        }
        String normalized = normalize(summary);
        long covered = facts.stream().filter(fact -> normalized.contains(normalize(fact))).count();
        return (double) covered / facts.size();
    }

    static List<String> missing(String summary, List<String> facts) {
        String normalized = normalize(summary);
        List<String> missing = new ArrayList<>();
        for (String fact : facts) {
            if (!normalized.contains(normalize(fact))) {
                missing.add(fact);
            }
        }
        return missing;
    }

    private static void collect(String value, Set<String> facts) {
        if (value == null || value.isBlank()) {
            return;
        }
        Matcher matcher = EXACT_FACT.matcher(value);
        while (matcher.find()) {
            facts.add(matcher.group());
        }
        matcher = KEY_STATEMENT.matcher(value);
        while (matcher.find()) {
            facts.add(matcher.group().trim());
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
