package com.example.ragagent.service;

import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ReActLoop {
    private final ToolRegistry toolRegistry;

    public ReActLoop(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public ReActLoopResult run(ChatRequest request, QueryAnalysisResponse analysis, int startStep) {
        List<AgentTraceStep> trace = new ArrayList<>();
        trace.add(new AgentTraceStep(
                startStep,
                "route",
                analysis.route(),
                "",
                "analyze",
                "intent=" + analysis.intent() + ", confidence=" + analysis.confidence()
        ));

        ToolDecision decision = toolRegistry.decide(request, analysis);
        trace.add(new AgentTraceStep(
                startStep + 1,
                "route",
                analysis.route(),
                decision.toolName(),
                "select_tool",
                decision.reason()
        ));

        if (!decision.useTool()) {
            return new ReActLoopResult(decision, null, trace);
        }

        AgentTool tool = toolRegistry.resolve(decision);
        AgentToolResult result = tool.execute(new AgentToolRequest(request, analysis, decision));
        trace.add(new AgentTraceStep(
                startStep + 2,
                "tool",
                analysis.route(),
                result.toolName(),
                "observe",
                result.observation()
        ));
        return new ReActLoopResult(decision, result, trace);
    }
}
