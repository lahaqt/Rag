package com.example.ragagent.service;

import java.time.Instant;
import java.time.Duration;
import java.util.List;

/** Verified, request-scoped evidence used for answer generation. */
final class EvidenceSet {
    record Evidence(String stepId, String executionId, Instant observedAt, AgentToolResult result) { }
    private final java.util.concurrent.CopyOnWriteArrayList<Evidence> values = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    void add(String stepId, String executionId, AgentToolResult result) {
        if (result != null && result.success()) values.add(new Evidence(stepId, executionId, Instant.now(), result));
    }
    List<AgentToolResult> validResults() { return values.stream().filter(this::valid).map(Evidence::result).toList(); }
    java.util.Optional<AgentToolResult> resultForStep(String stepId) {
        return values.stream().filter(this::valid).filter(value -> value.stepId().equals(stepId)).reduce((left, right) -> right).map(Evidence::result);
    }
    private boolean valid(Evidence evidence) { return evidence.observedAt().plus(MAX_AGE).isAfter(Instant.now()); }
}
