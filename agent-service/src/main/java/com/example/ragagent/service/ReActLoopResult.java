package com.example.ragagent.service;

import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.RetrievalHit;
import com.example.ragagent.dto.WebSearchResult;
import java.util.List;

/**
 * Aggregated result of the (now real) ReAct loop. Carries the planner's last
 * decision, the last tool observation, and the cumulative state observed across
 * every iteration.
 *
 * <p>Convenience accessors mirror the old single-pass behaviour for callers that
 * still go through {@link #retrievalHits()} / {@link #webSearchResults()}; when
 * the loop executed multiple iterations they return aggregated hits/results.
 */
public record ReActLoopResult(
        ToolDecision decision,
        AgentToolResult toolResult,
        List<AgentTraceStep> trace,
        ReActState state
) {
    public ReActLoopResult {
        trace = trace == null ? List.of() : List.copyOf(trace);
        state = state == null ? ReActState.initial() : state;
    }

    /** Convenience constructor for callers that only consume a single pass result. */
    public ReActLoopResult(ToolDecision decision, AgentToolResult toolResult, List<AgentTraceStep> trace) {
        this(decision, toolResult, trace, toolResult == null
                ? ReActState.initial()
                : ReActState.initial().withObservation(toolResult));
    }

    public int iterations() {
        return state.iteration();
    }

    public List<RetrievalHit> retrievalHits() {
        return state.retrievalHits();
    }

    public List<WebSearchResult> webSearchResults() {
        return state.webSearchResults();
    }
}