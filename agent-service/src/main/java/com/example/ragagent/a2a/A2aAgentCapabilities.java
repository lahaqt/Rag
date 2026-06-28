package com.example.ragagent.a2a;

public record A2aAgentCapabilities(
        boolean streaming,
        boolean pushNotifications,
        boolean stateTransitionHistory
) {
}
