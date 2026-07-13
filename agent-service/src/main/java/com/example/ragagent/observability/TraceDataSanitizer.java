package com.example.ragagent.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Removes obvious identifiers and secrets before traces leave request memory. */
public final class TraceDataSanitizer {
    private static final Pattern SECRET = Pattern.compile("(?i)(api[_-]?key|authorization|bearer|token|password)\\s*[:=]\\s*[^,;\\s]+");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private TraceDataSanitizer() { }
    public static String text(String value) {
        if (value == null) return "";
        return PHONE.matcher(SECRET.matcher(value).replaceAll("$1=***")).replaceAll("***");
    }
    public static Map<String, Object> attributes(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) return Map.of();
        source.forEach((key, value) -> copy.put(key, sensitive(key) ? "***" : value instanceof String text ? text(text) : value));
        return Map.copyOf(copy);
    }
    private static boolean sensitive(String key) { return key != null && key.matches("(?i).*?(token|secret|password|authorization|phone|mobile).*?"); }
}
