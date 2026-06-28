package com.example.ragagent.a2a;

import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.service.SpecialistAgentResult;
import java.util.List;

public record A2aTaskExecution(
        A2aTask task,
        SpecialistAgentResult agentResult,
        List<AgentTraceStep> trace
) {
    public A2aTaskExecution {
        trace = trace == null ? List.of() : List.copyOf(trace);
    }
}
