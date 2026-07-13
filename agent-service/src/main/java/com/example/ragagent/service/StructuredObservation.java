package com.example.ragagent.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Machine-readable evidence for Planner decisions and downstream bindings. */
public record StructuredObservation(String summary, Map<String, Object> data) {
    public StructuredObservation {
        summary = summary == null ? "" : summary;
        data = data == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(data));
    }

    public static StructuredObservation empty(String summary) {
        return new StructuredObservation(summary, Map.of());
    }
}
