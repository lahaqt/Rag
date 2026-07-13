package com.example.ragagent.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Provider-neutral next step emitted by the dynamic planner. */
public record ToolPlan(String capability, String toolKey, String query, Map<String, Object> arguments, String reason) {
    public ToolPlan {
        capability = capability == null ? "" : capability;
        toolKey = toolKey == null ? "" : toolKey;
        query = query == null ? "" : query;
        arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
        reason = reason == null ? "" : reason;
    }
}
