package com.example.ragagent.a2a;

import java.util.List;

public record A2aAgentSkill(
        String id,
        String name,
        String description,
        List<String> tags,
        List<String> examples,
        List<String> inputModes,
        List<String> outputModes
) {
    public A2aAgentSkill {
        tags = tags == null ? List.of() : List.copyOf(tags);
        examples = examples == null ? List.of() : List.copyOf(examples);
        inputModes = inputModes == null ? List.of("text/plain") : List.copyOf(inputModes);
        outputModes = outputModes == null ? List.of("text/plain") : List.copyOf(outputModes);
    }
}
