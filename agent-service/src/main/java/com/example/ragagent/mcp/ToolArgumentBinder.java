package com.example.ragagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Binds JSON-schema tool inputs from structured observations. */
public final class ToolArgumentBinder {
    private ToolArgumentBinder() {
    }

    public static BoundArguments bind(
            McpToolDescriptor descriptor,
            Map<String, Object> baseArguments,
            Map<String, Object> observation
    ) {
        Map<String, Object> arguments = new LinkedHashMap<>(baseArguments == null ? Map.of() : baseArguments);
        Set<String> boundProperties = new LinkedHashSet<>();
        JsonNode schema = descriptor == null ? null : descriptor.inputSchema();
        JsonNode properties = schema == null ? null : schema.path("properties");
        if (properties == null || !properties.isObject()) {
            return new BoundArguments(arguments, boundProperties);
        }
        properties.properties().forEach(entry -> {
            String name = entry.getKey();
            Optional<Object> value = find(observation, name);
            if (value.isPresent()) {
                arguments.put(name, value.get());
                boundProperties.add(name);
            } else if (arguments.containsKey(name)) {
                Object resolved = resolveReference(arguments.get(name), observation);
                if (resolved != arguments.get(name)) {
                    arguments.put(name, resolved);
                    boundProperties.add(name);
                }
            }
        });
        return new BoundArguments(arguments, boundProperties);
    }

    public static boolean requiredIdentifiersBound(McpToolDescriptor descriptor, Set<String> boundProperties) {
        JsonNode schema = descriptor == null ? null : descriptor.inputSchema();
        if (schema == null || !schema.path("required").isArray()) {
            return !boundProperties.isEmpty();
        }
        List<String> identifiers = new ArrayList<>();
        for (JsonNode required : schema.path("required")) {
            String name = required.asText("");
            if (isIdentifier(name)) {
                identifiers.add(name);
            }
        }
        return !identifiers.isEmpty() && boundProperties.containsAll(identifiers);
    }

    private static boolean isIdentifier(String name) {
        String normalized = normalize(name);
        return normalized.endsWith("id") || normalized.contains("code") || normalized.contains("number")
                || normalized.contains("no") || normalized.contains("token");
    }

    @SuppressWarnings("unchecked")
    private static Optional<Object> find(Object value, String target) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (sameKey(key, target) && simple(entry.getValue())) {
                    return Optional.ofNullable(entry.getValue());
                }
            }
            for (Object nested : map.values()) {
                Optional<Object> found = find(nested, target);
                if (found.isPresent()) {
                    return found;
                }
            }
        } else if (value instanceof Collection<?> values) {
            for (Object nested : values) {
                Optional<Object> found = find(nested, target);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static Object resolveReference(Object value, Map<String, Object> observation) {
        if (!(value instanceof String text)) {
            return value;
        }
        String normalized = text.trim();
        if (!normalized.startsWith("${observation.") || !normalized.endsWith("}")) {
            return value;
        }
        String field = normalized.substring("${observation.".length(), normalized.length() - 1);
        return find(observation, field).orElse(value);
    }

    private static boolean simple(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private static boolean sameKey(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    public record BoundArguments(Map<String, Object> arguments, Set<String> boundProperties) {
        public BoundArguments {
            arguments = Map.copyOf(arguments);
            boundProperties = Set.copyOf(boundProperties);
        }
    }
}
