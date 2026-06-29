package com.example.ragagent.controller;

import com.example.ragagent.observability.AgentTracePersistenceService;
import com.example.ragagent.observability.AgentTraceRecord;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/traces")
public class TraceController {
    private final AgentTracePersistenceService tracePersistenceService;

    public TraceController(AgentTracePersistenceService tracePersistenceService) {
        this.tracePersistenceService = tracePersistenceService;
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
}
