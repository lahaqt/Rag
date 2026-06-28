package com.example.ragagent.a2a;

import java.util.List;

public final class A2aCards {
    private A2aCards() {
    }

    public static A2aAgentCard specialist(
            String id,
            String name,
            String description,
            A2aAgentSkill skill
    ) {
        return new A2aAgentCard(
                "0.3.0",
                id,
                name,
                description,
                "/api/chat/multi-agent/agents/" + id,
                "0.1.0",
                new A2aAgentCapabilities(false, false, true),
                List.of("text/plain"),
                List.of("text/plain", "application/json"),
                List.of(skill)
        );
    }
}
