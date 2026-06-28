package com.example.ragagent.memory;

import java.util.Map;

public record MemoryStateContext(Map<String, String> values) {
    public MemoryStateContext {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
