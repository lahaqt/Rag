package com.example.ragagent.controller;

import com.example.ragagent.history.ConversationHistoryService;
import com.example.ragagent.history.ConversationMessageRecord;
import com.example.ragagent.history.ConversationSummary;
import com.example.ragagent.history.CreateConversationRequest;
import com.example.ragagent.history.UpdateConversationRequest;
import com.example.ragagent.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationHistoryService conversationHistoryService;

    public ConversationController(ConversationHistoryService conversationHistoryService) {
        this.conversationHistoryService = conversationHistoryService;
    }

    @GetMapping
    public List<ConversationSummary> list(
            HttpServletRequest httpRequest,
            @RequestParam(defaultValue = "") String userId,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String knowledgeBaseId,
            @RequestParam(required = false) Boolean archived,
            @RequestParam(defaultValue = "false") Boolean includeDeleted
    ) {
        return conversationHistoryService.list(RequestIdentity.requireMatchingUser(httpRequest, userId), query, knowledgeBaseId, archived, includeDeleted);
    }

    @PostMapping
    public ConversationSummary create(HttpServletRequest httpRequest, @RequestBody(required = false) CreateConversationRequest request) {
        if (request == null) throw new IllegalArgumentException("Conversation request is required.");
        String userId = RequestIdentity.requireMatchingUser(httpRequest, request.userId());
        return conversationHistoryService.create(new CreateConversationRequest(userId, request.title(), request.knowledgeBaseId()));
    }

    @GetMapping("/{id}")
    public ConversationSummary get(HttpServletRequest httpRequest, @PathVariable String id, @RequestParam(defaultValue = "") String userId) {
        return conversationHistoryService.get(id, RequestIdentity.requireMatchingUser(httpRequest, userId));
    }

    @GetMapping("/{id}/messages")
    public List<ConversationMessageRecord> messages(
            HttpServletRequest httpRequest,
            @PathVariable String id,
            @RequestParam(defaultValue = "") String userId
    ) {
        return conversationHistoryService.messages(id, RequestIdentity.requireMatchingUser(httpRequest, userId));
    }

    @PatchMapping("/{id}")
    public ConversationSummary update(HttpServletRequest httpRequest, @PathVariable String id, @RequestBody UpdateConversationRequest request) {
        if (request == null) throw new IllegalArgumentException("Conversation update request is required.");
        String userId = RequestIdentity.requireMatchingUser(httpRequest, request.userId());
        return conversationHistoryService.update(id, new UpdateConversationRequest(
                userId, request.title(), request.knowledgeBaseId(), request.pinned(), request.archived(), request.deleted()
        ));
    }

    @DeleteMapping("/{id}")
    public ConversationSummary delete(HttpServletRequest httpRequest, @PathVariable String id, @RequestParam(defaultValue = "") String userId) {
        return conversationHistoryService.update(id, new UpdateConversationRequest(
                RequestIdentity.requireMatchingUser(httpRequest, userId), null, null, null, null, true
        ));
    }
}
