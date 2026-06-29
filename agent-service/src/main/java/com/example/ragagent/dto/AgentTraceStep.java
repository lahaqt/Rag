package com.example.ragagent.dto;

import java.util.Map;

public record AgentTraceStep(
        int step,
        String phase,
        String route,
        String toolName,
        String action,
        String observation,
        String status,
        long durationMs,
        String error,
        String traceId,
        String spanId,
        Map<String, Object> attributes
) {
    public AgentTraceStep {
        phase = safe(phase);
        route = safe(route);
        toolName = safe(toolName);
        action = safe(action);
        observation = safe(observation);
        status = status == null || status.isBlank() ? "ok" : status;
        durationMs = Math.max(-1, durationMs);
        error = safe(error);
        traceId = safe(traceId);
        spanId = safe(spanId);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public AgentTraceStep(
            int step,
            String phase,
            String route,
            String toolName,
            String action,
            String observation
    ) {
        this(step, phase, route, toolName, action, observation, "ok", -1, "", "", "", Map.of());
    }

    public static AgentTraceStep timed(
            int step,
            String phase,
            String route,
            String toolName,
            String action,
            String observation,
            String status,
            long durationMs
    ) {
        return new AgentTraceStep(step, phase, route, toolName, action, observation, status, durationMs, "", "", "", Map.of());
    }

    public static AgentTraceStep failed(
            int step,
            String phase,
            String route,
            String toolName,
            String action,
            String observation,
            long durationMs,
            Exception exception
    ) {
        String message = exception == null ? "" : exception.getMessage();
        return new AgentTraceStep(
                step,
                phase,
                route,
                toolName,
                action,
                observation,
                "error",
                durationMs,
                message == null ? exception.getClass().getSimpleName() : message,
                "",
                "",
                Map.of()
        );
    }

    public AgentTraceStep withTrace(String traceId, String spanId) {
        return new AgentTraceStep(
                step,
                phase,
                route,
                toolName,
                action,
                observation,
                status,
                durationMs,
                error,
                traceId,
                spanId,
                attributes
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
