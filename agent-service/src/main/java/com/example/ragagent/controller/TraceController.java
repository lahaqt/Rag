package com.example.ragagent.controller;

import com.example.ragagent.observability.AgentTracePersistenceService;
import com.example.ragagent.observability.AgentTraceRecord;
import com.example.ragagent.observability.AgentRunRecord;
import com.example.ragagent.observability.AgentRunStepRecord;
import com.example.ragagent.service.AgentExecutionRecoveryService;
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

    public TraceController(
            AgentTracePersistenceService tracePersistenceService,
            AgentExecutionRecoveryService executionRecoveryService
    ) {
        this.tracePersistenceService = tracePersistenceService;
        this.executionRecoveryService = executionRecoveryService;
    }

    @GetMapping("/{traceId}")
    public List<AgentTraceRecord> findByTraceId(@PathVariable String traceId) {
        return tracePersistenceService.findByTraceId(traceId);
    }

    @GetMapping
    public List<AgentTraceRecord> listByConversation(
            @RequestParam String conversationId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return tracePersistenceService.listByConversation(conversationId, limit);
    }

    @GetMapping("/runs/{runId}")
    public AgentRunRecord findRun(@PathVariable String runId) {
        return tracePersistenceService.findRun(runId);
    }

    @GetMapping("/runs/{runId}/steps")
    public List<AgentRunStepRecord> findRunSteps(@PathVariable String runId) {
        return tracePersistenceService.findRunSteps(runId);
    }

    @PostMapping("/runs/{runId}/resume")
    public AgentRunRecord resumeRun(@PathVariable String runId) {
        if (!executionRecoveryService.resumeExplicitly(runId)) {
            throw new IllegalArgumentException("Run is unavailable for recovery: " + runId);
        }
        return tracePersistenceService.findRun(runId);
    }
}
