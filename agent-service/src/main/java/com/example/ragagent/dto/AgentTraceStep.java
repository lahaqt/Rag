package com.example.ragagent.dto;

public record AgentTraceStep(
        int step,
        String phase,
        String route,
        String toolName,
        String action,
        String observation
) {
}
