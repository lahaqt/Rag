package com.example.ragagent.a2a;

import java.util.Map;

public record A2aPart(
        String kind,
        String text,
        Map<String, Object> data
) {
    public A2aPart {
        if (kind == null || kind.isBlank()) {
            kind = text == null ? "data" : "text";
        }
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static A2aPart text(String value) {
        return new A2aPart("text", value == null ? "" : value, Map.of());
    }

    public static A2aPart data(Map<String, Object> value) {
        return new A2aPart("data", "", value);
    }
}
