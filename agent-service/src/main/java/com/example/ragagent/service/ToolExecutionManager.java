package com.example.ragagent.service;

import java.util.function.Supplier;

/** Request-scoped execution gate shared by every capability and graph branch. */
final class ToolExecutionManager {
    AgentToolResult execute(AgentExecutionContext context, String toolName, String query, Supplier<AgentToolResult> operation) {
        if (!context.reserveToolAttempt()) {
            return AgentToolResult.failure(toolName, query, "tool_budget_or_deadline_exhausted", "tool_budget_exhausted");
        }
        return operation.get();
    }
}
