package com.example.ragagent.service;

import java.util.Locale;

/** Central side-effect policy: only explicitly read-like business tools may run without confirmation. */
final class ToolSafetyPolicy {
    boolean isReadOnly(PlanStep step) {
        if ("rag_retrieval".equals(step.capability) || "web_search".equals(step.capability)) return true;
        ToolPlan plan = step.actualTool() == null ? step.plannedTool : step.actualTool();
        if (plan == null) return false;
        String key = plan.toolKey().toLowerCase(Locale.ROOT);
        return key.matches(".*(get|query|search|list|lookup|read|fetch|track|status).*" );
    }

    boolean requiresConfirmation(PlanStep step, ToolPlan plan) {
        if ("rag_retrieval".equals(step.capability) || "web_search".equals(step.capability)) return false;
        String key = plan == null ? "" : plan.toolKey().toLowerCase(Locale.ROOT);
        return key.matches(".*(create|update|delete|cancel|refund|pay|write|send|submit).*" );
    }
}
