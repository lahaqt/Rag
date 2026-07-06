package com.example.ragagent.a2a;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import java.util.List;
import java.util.Map;

public final class A2aAgentCards {
    private A2aAgentCards() {
    }

    public static AgentCard specialist(String id, String name, String description, AgentSkill skill) {
        return new AgentCard.Builder()
                .name(id)
                .description(description)
                .url("/api/chat/multi-agent/agents/" + id)
                .version("0.1.0")
                .capabilities(new AgentCapabilities(false, false, true, List.of()))
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain", "application/json"))
                .skills(List.of(skill))
                .supportsAuthenticatedExtendedCard(false)
                .securitySchemes(Map.of())
                .security(List.of())
                .additionalInterfaces(List.of())
                .preferredTransport("JSONRPC")
                .protocolVersion("0.3.0")
                .build();
    }
}
