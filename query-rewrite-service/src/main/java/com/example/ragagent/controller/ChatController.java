package com.example.ragagent.controller;

import com.example.ragagent.dto.ChatQueryRequest;
import com.example.ragagent.dto.ChatQueryAnalysisResponse;
import com.example.ragagent.dto.ChatQueryRewriteResponse;
import com.example.ragagent.service.ChatQueryAnalysis;
import com.example.ragagent.service.ChatQueryAnalysisService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatQueryAnalysisService chatQueryAnalysisService;

    public ChatController(ChatQueryAnalysisService chatQueryAnalysisService) {
        this.chatQueryAnalysisService = chatQueryAnalysisService;
    }

    @PostMapping("/query-rewrite")
    public ChatQueryRewriteResponse rewriteQuery(@Valid @RequestBody ChatQueryRequest request) {
        ChatQueryAnalysis analysis = chatQueryAnalysisService.analyze(request);
        return new ChatQueryRewriteResponse(
                analysis.originalQuery(),
                analysis.rewrittenQuery(),
                analysis.intent().value(),
                analysis.confidence(),
                analysis.route(),
                analysis.rewritten(),
                analysis.retrievalQueries(),
                analysis.reasons()
        );
    }

    @PostMapping("/analyze")
    public ChatQueryAnalysisResponse analyzeQuery(@Valid @RequestBody ChatQueryRequest request) {
        return toResponse(chatQueryAnalysisService.analyze(request));
    }

    private ChatQueryAnalysisResponse toResponse(ChatQueryAnalysis analysis) {
        return new ChatQueryAnalysisResponse(
                analysis.sessionId(),
                analysis.knowledgeBaseId(),
                analysis.originalQuery(),
                analysis.normalizedQuery(),
                analysis.rewrittenQuery(),
                analysis.intent().value(),
                analysis.confidence(),
                analysis.route(),
                analysis.needsRewrite(),
                analysis.rewritten(),
                analysis.historyLength(),
                analysis.retrievalQueries(),
                analysis.reasons()
        );
    }
}
