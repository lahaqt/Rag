package com.example.ragagent.controller;

import com.example.ragagent.history.ConversationHistoryService;
import com.example.ragagent.history.ConversationMessageRecord;
import com.example.ragagent.history.ConversationSummary;
import com.example.ragagent.history.CreateConversationRequest;
import com.example.ragagent.history.UpdateConversationRequest;
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
            @RequestParam(defaultValue = "") String userId,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String knowledgeBaseId,
            @RequestParam(required = false) Boolean archived,
            @RequestParam(defaultValue = "false") Boolean includeDeleted
    ) {
        return conversationHistoryService.list(userId, query, knowledgeBaseId, archived, includeDeleted);
    }

    @PostMapping
    public ConversationSummary create(@RequestBody(required = false) CreateConversationRequest request) {
        return conversationHistoryService.create(request);
    }

    @GetMapping("/{id}")
    public ConversationSummary get(@PathVariable String id, @RequestParam(defaultValue = "") String userId) {
        return conversationHistoryService.get(id, userId);
    }

    @GetMapping("/{id}/messages")
    public List<ConversationMessageRecord> messages(
            @PathVariable String id,
            @RequestParam(defaultValue = "") String userId
    ) {
        return conversationHistoryService.messages(id, userId);
    }

    @PatchMapping("/{id}")
    public ConversationSummary update(@PathVariable String id, @RequestBody UpdateConversationRequest request) {
        return conversationHistoryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ConversationSummary delete(@PathVariable String id, @RequestParam(defaultValue = "") String userId) {
        return conversationHistoryService.update(id, new UpdateConversationRequest(userId, null, null, null, null, true));
    }
}
