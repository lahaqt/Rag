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
import java.util.List;
import java.util.LinkedHashSet;
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
        conversationMemoryService.recordTurn(context.analysisRequest, context.analysis, response);
        if (conversationHistoryService != null) {
            conversationHistoryService.recordTurn(context.analysisRequest, response);
        }
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
        String answer = ensureCitationMarkers(context.draft.answer(), allHits);
        List<RetrievalHit> citedHits = citedHits(allHits, answer);
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
                citedHits.stream()
                        .map(hit -> hit.toCitation(citationExcerptExtractor.extract(
                                hit,
                                context.analysis.originalQuery(),
                                context.analysis.rewrittenQuery(),
                                answer
                        )))
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
     * The graph runtime owns the final answer assembly. Preserve the citation
     * guarantee even when the model ignores the prompt's citation instruction.
     */
    private String ensureCitationMarkers(String answer, List<RetrievalHit> hits) {
        if (answer == null || answer.isBlank() || hits.isEmpty() || CITATION_MARKER.matcher(answer).find()) {
            return answer;
        }
        String markers = hits.stream()
                .map(RetrievalHit::index)
                .distinct()
                .limit(3)
                .map(index -> "[" + index + "]")
                .reduce("", (left, right) -> left.isEmpty() ? right : left + " " + right);
        return markers.isEmpty() ? answer : answer.stripTrailing() + "\n\n" + markers;
    }

    private List<RetrievalHit> citedHits(List<RetrievalHit> hits, String answer) {
        Set<Integer> indexes = new LinkedHashSet<>();
        var matcher = CITATION_MARKER.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            indexes.add(Integer.parseInt(matcher.group(1)));
        }
        return hits.stream().filter(hit -> indexes.contains(hit.index())).toList();
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
