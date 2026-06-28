package com.example.ragagent.service;

/**
 * Typed plan step used by the iterative plan-execute-reflect pipeline.
 *
 * <p>The {@code kind} is a stable identifier consumed by {@link AgentPlanner} /
 * {@link ReActLoop} to decide whether the step is actionable (calls a tool)
 * or symbolic (representational only). The {@code label} is used in tracing
 * and human-readable output.
 */
public record AgentPlanStep(
        String label,
        String kind,
        String status
) {
    public static final String KIND_ANALYZE_QUERY = "analyze_query";
    public static final String KIND_RETRIEVE_KNOWLEDGE = "retrieve_knowledge";
    public static final String KIND_ROUTE_TOOL = "route_tool";
    public static final String KIND_DIRECT_ANSWER = "direct_answer";
    public static final String KIND_GENERATE_ANSWER = "generate_answer";
    public static final String KIND_REFLECT = "reflect";
    public static final String KIND_OBSERVE = "observe";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_SKIPPED = "skipped";

    public static AgentPlanStep pending(String label, String kind) {
        return new AgentPlanStep(label, kind, STATUS_PENDING);
    }

    public AgentPlanStep done() {
        return new AgentPlanStep(label, kind, STATUS_DONE);
    }

    public AgentPlanStep skipped() {
        return new AgentPlanStep(label, kind, STATUS_SKIPPED);
    }
}