package com.example.ragagent.service;

import com.example.ragagent.dto.RetrievalHit;
import com.example.ragagent.dto.WebSearchResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable-by-copy state threaded through every iteration of {@link ReActLoop}.
 *
 * <p>Aggregates all tool observations and the merged retrieval hits / web search
 * results they produced so that downstream answer generation can consume the
 * full picture after the loop terminates.
 */
public record ReActState(
        int iteration,
        List<AgentToolResult> observations,
        List<RetrievalHit> aggregatedHits,
        List<WebSearchResult> aggregatedWebResults
) {
    public ReActState {
        observations = observations == null ? List.of() : List.copyOf(observations);
        aggregatedHits = aggregatedHits == null ? List.of() : List.copyOf(aggregatedHits);
        aggregatedWebResults = aggregatedWebResults == null
                ? List.of()
                : List.copyOf(aggregatedWebResults);
    }

    public static ReActState initial() {
        return new ReActState(0, List.of(), List.of(), List.of());
    }

    public ReActState withObservation(AgentToolResult result) {
        if (result == null) {
            return new ReActState(iteration + 1, observations, aggregatedHits, aggregatedWebResults);
        }
        List<AgentToolResult> updated = new ArrayList<>(observations);
        updated.add(result);
        return new ReActState(
                iteration + 1,
                updated,
                mergeHits(aggregatedHits, result.retrievalHits()),
                mergeWeb(aggregatedWebResults, result.webSearchResults())
        );
    }

    public List<RetrievalHit> retrievalHits() {
        return aggregatedHits;
    }

    public List<WebSearchResult> webSearchResults() {
        return aggregatedWebResults;
    }

    public AgentToolResult lastObservation() {
        return observations.isEmpty() ? null : observations.get(observations.size() - 1);
    }

    public ToolDecision lastDecision(List<ToolDecision> decisions) {
        return decisions.isEmpty() ? ToolDecision.none() : decisions.get(decisions.size() - 1);
    }

    private static List<RetrievalHit> mergeHits(List<RetrievalHit> existing, List<RetrievalHit> added) {
        if (added == null || added.isEmpty()) {
            return existing;
        }
        List<RetrievalHit> merged = new ArrayList<>(existing);
        merged.addAll(added);
        return List.copyOf(merged);
    }

    private static List<WebSearchResult> mergeWeb(List<WebSearchResult> existing, List<WebSearchResult> added) {
        if (added == null || added.isEmpty()) {
            return existing;
        }
        List<WebSearchResult> merged = new ArrayList<>(existing);
        merged.addAll(added);
        return List.copyOf(merged);
    }
}