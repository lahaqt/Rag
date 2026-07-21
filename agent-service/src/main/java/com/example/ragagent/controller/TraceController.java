package com.example.ragagent.controller;

import com.example.ragagent.observability.AgentTracePersistenceService;
import com.example.ragagent.observability.AgentTraceRecord;
import com.example.ragagent.observability.AgentRunRecord;
import com.example.ragagent.observability.AgentRunStepRecord;
import com.example.ragagent.history.ConversationHistoryService;
import com.example.ragagent.security.RequestIdentity;
import com.example.ragagent.service.AgentExecutionRecoveryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/traces")
public class TraceController {
    private final AgentTracePersistenceService tracePersistenceService;
    private final AgentExecutionRecoveryService executionRecoveryService;
    private final ConversationHistoryService conversationHistoryService;

    public TraceController(
            AgentTracePersistenceService tracePersistenceService,
            AgentExecutionRecoveryService executionRecoveryService,
            ConversationHistoryService conversationHistoryService
    ) {
        this.tracePersistenceService = tracePersistenceService;
        this.executionRecoveryService = executionRecoveryService;
        this.conversationHistoryService = conversationHistoryService;
    }

    @GetMapping("/{traceId}")
    public List<AgentTraceRecord> findByTraceId(HttpServletRequest httpRequest, @PathVariable String traceId) {
        List<AgentTraceRecord> records = tracePersistenceService.findByTraceId(traceId);
        records.forEach(record -> requireConversationOwner(httpRequest, record.conversationId()));
        return records;
    }

    @GetMapping
    public List<AgentTraceRecord> listByConversation(
            HttpServletRequest httpRequest,
            @RequestParam String conversationId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        requireConversationOwner(httpRequest, conversationId);
        return tracePersistenceService.listByConversation(conversationId, limit);
    }

    @GetMapping("/runs/{runId}")
    public AgentRunRecord findRun(HttpServletRequest httpRequest, @PathVariable String runId) {
        AgentRunRecord run = tracePersistenceService.findRun(runId);
        requireRunOwner(httpRequest, run);
        return run;
    }

    @GetMapping("/runs/{runId}/steps")
    public List<AgentRunStepRecord> findRunSteps(HttpServletRequest httpRequest, @PathVariable String runId) {
        requireRunOwner(httpRequest, tracePersistenceService.findRun(runId));
        return tracePersistenceService.findRunSteps(runId);
    }

    @PostMapping("/runs/{runId}/resume")
    public AgentRunRecord resumeRun(HttpServletRequest httpRequest, @PathVariable String runId) {
        requireRunOwner(httpRequest, tracePersistenceService.findRun(runId));
        if (!executionRecoveryService.resumeExplicitly(runId)) {
            throw new IllegalArgumentException("Run is unavailable for recovery: " + runId);
        }
        return tracePersistenceService.findRun(runId);
    }

    private void requireRunOwner(HttpServletRequest request, AgentRunRecord run) {
        if (run == null) {
            return;
        }
        requireConversationOwner(request, run.conversationId());
    }

    private void requireConversationOwner(HttpServletRequest request, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Trace records without a conversation owner are not externally accessible"
            );
        }
        conversationHistoryService.get(conversationId, RequestIdentity.requiredUserId(request));
    }
}
