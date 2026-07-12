package com.example.ragagent.service;

import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.dto.RetrievalHit;
import com.example.ragagent.dto.WebSearchResult;
import com.example.ragagent.history.ConversationHistoryService;
import com.example.ragagent.memory.ConversationMemoryService;
import com.example.ragagent.observability.AgentTracePersistenceService;
import com.example.ragagent.observability.TraceContextProvider;
import com.example.ragagent.observability.TraceContextSnapshot;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Converts completed graph state into a durable chat response. */
final class AgentResponseFinalizer {
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(\\d+)]");
    private final ConversationMemoryService conversationMemoryService;
    private final ConversationHistoryService conversationHistoryService;
    private final TraceContextProvider traceContextProvider;
    private final AgentTracePersistenceService tracePersistenceService;
    private final CitationExcerptExtractor citationExcerptExtractor = new CitationExcerptExtractor();

    AgentResponseFinalizer(
            ConversationMemoryService conversationMemoryService,
            ConversationHistoryService conversationHistoryService,
            TraceContextProvider traceContextProvider,
            AgentTracePersistenceService tracePersistenceService
    ) {
        this.conversationMemoryService = conversationMemoryService;
        this.conversationHistoryService = conversationHistoryService;
        this.traceContextProvider = traceContextProvider;
        this.tracePersistenceService = tracePersistenceService;
    }

    ChatResponse finalizeResponse(AgentExecutionContext context, String graphName) {
        context.addTrace(new AgentTraceStep(
                context.nextStep(),
                "request",
                context.analysis.route(),
                context.decision.toolName(),
                "complete",
                "graph=" + graphName
                        + "; finishReason=" + context.draft.finishReason()
                        + "; durationScope=graph_execution"
                        + "; excludes=sse_queue,post_process,response_transfer",
                "ok",
                durationMs(context.requestStarted),
                "",
                "",
                "",
                Map.of("durationScope", "graph_total")
        ));
        ChatResponse response = withCurrentTrace(response(context), context.fallbackTraceContext);
        context.response = response;
        if (tracePersistenceService != null) {
            tracePersistenceService.completeRun(context.runId, response);
            tracePersistenceService.record(context.request, response);
        }
        if (conversationHistoryService != null) {
            conversationHistoryService.recordTurn(context.analysisRequest, response);
        }
        // Persist the canonical turn before updating the derived memory projection.
        conversationMemoryService.recordTurn(context.analysisRequest, context.analysis, response);
        return response;
    }

    private ChatResponse response(AgentExecutionContext context) {
        AgentToolResult result = context.toolResult;
        ToolDecision decision = context.decision;
        List<RetrievalHit> allHits = result == null ? List.of() : result.retrievalHits();
        boolean includeRetrievalDebug = context.request.options() != null
                && Boolean.TRUE.equals(context.request.options().includeRetrievalDebug());
        List<RetrievalHit> debugHits = includeRetrievalDebug ? allHits : List.of();
        List<WebSearchResult> webResults = result == null ? List.of() : result.webSearchResults();
        String answer = retainVerifiedCitationMarkers(context.draft.answer(), allHits);
        Map<Integer, String> citationClaims = citationClaims(answer, allHits);
        String route = "web_search".equals(decision.toolName()) ? "web_search" : context.analysis.route();
        String intent = "web_search".equals(decision.toolName()) ? "tool" : context.analysis.intent();
        List<String> retrievalQueries = "web_search".equals(decision.toolName())
                ? List.of(decision.query())
                : context.analysis.safeRetrievalQueries();
        return new ChatResponse(
                context.request.conversationId(),
                answer,
                intent,
                context.analysis.confidence(),
                route,
                context.analysis.originalQuery(),
                context.analysis.rewrittenQuery(),
                retrievalQueries,
                allHits.stream()
                        .filter(hit -> citationClaims.containsKey(hit.index()))
                        .map(hit -> {
                            String claim = citationClaims.get(hit.index());
                            return hit.toCitation(
                                    citationExcerptExtractor.extract(
                                            hit,
                                            context.analysis.originalQuery(),
                                            context.analysis.rewrittenQuery(),
                                            claim
                                    ),
                                    claim
                            );
                        })
                        .toList(),
                debugHits,
                context.draft.llmUsed(),
                context.draft.finishReason(),
                decision.useTool() ? decision.toolName() : "",
                webResults,
                context.analysis.requestType(),
                context.analysis.executionMode(),
                context.analysis.safeRequiredCapabilities(),
                context.analysis.clarificationQuestion(),
                "",
                "",
                List.copyOf(context.trace)
        );
    }

    /**
     * Citations are evidence links, not decorative footer text. Retain only
     * markers that resolve to the retrieval results used for this response.
     */
    private String retainVerifiedCitationMarkers(String answer, List<RetrievalHit> hits) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }
        Set<Integer> allowedIndexes = hitIndexes(hits);
        var matcher = CITATION_MARKER.matcher(answer);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            matcher.appendReplacement(sanitized, allowedIndexes.contains(index) ? matcher.group() : "");
        }
        matcher.appendTail(sanitized);
        return sanitized.toString().replaceAll("[ \\t]+([,.!?。！？])", "$1");
    }

    private Map<Integer, String> citationClaims(String answer, List<RetrievalHit> hits) {
        Set<Integer> allowedIndexes = hitIndexes(hits);
        Map<Integer, String> claims = new LinkedHashMap<>();
        var matcher = CITATION_MARKER.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (allowedIndexes.contains(index)) {
                claims.putIfAbsent(index, sentenceAround(answer, matcher.start(), matcher.end()));
            }
        }
        return claims;
    }

    private Set<Integer> hitIndexes(List<RetrievalHit> hits) {
        Set<Integer> indexes = new LinkedHashSet<>();
        for (RetrievalHit hit : hits) {
            indexes.add(hit.index());
        }
        return indexes;
    }

    private String sentenceAround(String answer, int markerStart, int markerEnd) {
        int start = markerStart;
        while (start > 0 && !isSentenceBoundary(answer.charAt(start - 1))) {
            start--;
        }
        int end = markerEnd;
        while (end < answer.length() && !isSentenceBoundary(answer.charAt(end))) {
            end++;
        }
        if (end < answer.length()) {
            end++;
        }
        String claim = answer.substring(start, end);
        return CITATION_MARKER.matcher(claim)
                .replaceAll("")
                .replaceAll("\\s+", " ")
                .replaceAll("\\s+([,.!?。！？])", "$1")
                .trim();
    }

    private boolean isSentenceBoundary(char value) {
        return value == '\n' || value == '。' || value == '！' || value == '？'
                || value == '.' || value == '!' || value == '?';
    }

    private ChatResponse withCurrentTrace(ChatResponse response, TraceContextSnapshot fallbackTraceContext) {
        if (fallbackTraceContext != null && fallbackTraceContext.available()) {
            return response.withTrace(fallbackTraceContext.traceId(), fallbackTraceContext.spanId());
        }
        if (traceContextProvider != null) {
            TraceContextSnapshot current = traceContextProvider.current();
            if (current.available()) {
                return response.withTrace(current.traceId(), current.spanId());
            }
        }
        return response;
    }

    private long durationMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
