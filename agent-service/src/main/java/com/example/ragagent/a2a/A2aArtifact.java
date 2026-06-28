package com.example.ragagent.a2a;

import java.util.List;

public record A2aArtifact(
        String artifactId,
        String name,
        String description,
        List<A2aPart> parts
) {
    public A2aArtifact {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
