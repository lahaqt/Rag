package com.example.ragagent.a2a;

import java.util.List;

public record A2aTask(
        String id,
        String contextId,
        A2aTaskStatus status,
        List<A2aMessage> history,
        List<A2aArtifact> artifacts
) {
    public A2aTask {
        history = history == null ? List.of() : List.copyOf(history);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }
}
