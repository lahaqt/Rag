package com.example.ragagent.service;

public record SupervisorDecision(
        String agentName,
        String route,
        String reason
) {
}
