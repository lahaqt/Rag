package com.example.ragagent.observability;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Removes obvious identifiers and secrets before traces leave request memory. */
public final class TraceDataSanitizer {
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(api[_-]?key|access[_-]?key|client[_-]?secret|authorization|token|password|passwd|secret|credential)"
                    + "\\s*(?:[:=]|\\bis\\b)\\s*(?:bearer\\s+)?[^,;\\s]+"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bbearer\\s+[a-z0-9._~+/=-]{6,}");
    private static final Pattern BASIC_AUTH_URL = Pattern.compile("(?i)(https?://)[^/@\\s:]+:[^/@\\s]+@");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private TraceDataSanitizer() { }
    public static String text(String value) {
        if (value == null) return "";
        String sanitized = BASIC_AUTH_URL.matcher(value).replaceAll("$1***:***@");
        sanitized = PRIVATE_KEY.matcher(sanitized).replaceAll("***");
        sanitized = SECRET_ASSIGNMENT.matcher(sanitized).replaceAll("$1=***");
        sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("Bearer ***");
        return PHONE.matcher(sanitized).replaceAll("***");
    }
    public static Map<String, Object> attributes(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) return Map.of();
        source.forEach((key, value) -> copy.put(key, sensitive(key) ? "***" : value(value)));
        return Collections.unmodifiableMap(copy);
    }
    private static Object value(Object source) {
        if (source instanceof String text) return text(text);
        if (source instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> nested.put(String.valueOf(key), sensitive(String.valueOf(key)) ? "***" : value(item)));
            return Collections.unmodifiableMap(nested);
        }
        if (source instanceof Iterable<?> values) {
            ArrayList<Object> copy = new ArrayList<>();
            values.forEach(item -> copy.add(value(item)));
            return Collections.unmodifiableList(copy);
        }
        if (source != null && source.getClass().isArray()) {
            ArrayList<Object> copy = new ArrayList<>();
            for (int index = 0; index < Array.getLength(source); index++) copy.add(value(Array.get(source, index)));
            return Collections.unmodifiableList(copy);
        }
        return source;
    }
    private static boolean sensitive(String key) {
        return key != null && key.matches("(?i).*?(token|secret|password|authorization|credential|api[_-]?key|access[_-]?key|cookie|session|private[_-]?key|phone|mobile).*?");
    }
}
