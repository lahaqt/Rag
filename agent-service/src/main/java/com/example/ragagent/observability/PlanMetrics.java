package com.example.ragagent.observability;

import io.micrometer.core.instrument.Metrics;

/** Low-cardinality scheduler metrics; identifiers stay in Trace, never metric tags. */
public final class PlanMetrics {
    private PlanMetrics() { }
    public static void decision(String type, String reason) {
        Metrics.counter("rag.agent.plan.decisions", "type", safe(type), "reason", safe(reason)).increment();
    }
    public static void step(String capability, String status) {
        Metrics.counter("rag.agent.plan.steps", "capability", safe(capability), "status", safe(status)).increment();
    }
    private static String safe(String value) { return value == null || value.isBlank() ? "unknown" : value; }
}
