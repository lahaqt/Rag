package com.example.ragagent.a2a;

import java.util.List;

public record A2aMessage(
        String role,
        String messageId,
        String contextId,
        String taskId,
        List<String> referenceTaskIds,
        List<A2aPart> parts
) {
    public A2aMessage {
        referenceTaskIds = referenceTaskIds == null ? List.of() : List.copyOf(referenceTaskIds);
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
