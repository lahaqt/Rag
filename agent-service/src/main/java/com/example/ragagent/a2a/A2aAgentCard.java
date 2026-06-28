package com.example.ragagent.a2a;

import java.util.List;

public record A2aAgentCard(
        String protocolVersion,
        String id,
        String name,
        String description,
        String url,
        String version,
        A2aAgentCapabilities capabilities,
        List<String> defaultInputModes,
        List<String> defaultOutputModes,
        List<A2aAgentSkill> skills
) {
    public A2aAgentCard {
        protocolVersion = protocolVersion == null || protocolVersion.isBlank() ? "0.3.0" : protocolVersion;
        defaultInputModes = defaultInputModes == null ? List.of("text/plain") : List.copyOf(defaultInputModes);
        defaultOutputModes = defaultOutputModes == null ? List.of("text/plain") : List.copyOf(defaultOutputModes);
        skills = skills == null ? List.of() : List.copyOf(skills);
        if (capabilities == null) {
            capabilities = new A2aAgentCapabilities(false, false, true);
        }
    }
}
