package com.example.ragagent.a2a;

import java.time.Instant;

public record A2aTaskStatus(
        A2aTaskState state,
        A2aMessage message,
        Instant timestamp
) {
    public A2aTaskStatus {
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
