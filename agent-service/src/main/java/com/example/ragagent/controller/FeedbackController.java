package com.example.ragagent.controller;

import com.example.ragagent.dto.FeedbackRecord;
import com.example.ragagent.dto.FeedbackRequest;
import com.example.ragagent.history.ConversationHistoryService;
import com.example.ragagent.observability.FeedbackPersistenceService;
import com.example.ragagent.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {
    private final FeedbackPersistenceService feedbackPersistenceService;
    private final ConversationHistoryService conversationHistoryService;

    public FeedbackController(
            FeedbackPersistenceService feedbackPersistenceService,
            ConversationHistoryService conversationHistoryService
    ) {
        this.feedbackPersistenceService = feedbackPersistenceService;
        this.conversationHistoryService = conversationHistoryService;
    }

    @PostMapping
    public FeedbackRecord submit(HttpServletRequest httpRequest, @Valid @RequestBody FeedbackRequest request) {
        requireConversationOwner(httpRequest, request.conversationId());
        return feedbackPersistenceService.save(request);
    }

    @GetMapping
    public List<FeedbackRecord> list(
            HttpServletRequest httpRequest,
            @RequestParam String conversationId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        requireConversationOwner(httpRequest, conversationId);
        return feedbackPersistenceService.listByConversation(conversationId, limit);
    }

    private void requireConversationOwner(HttpServletRequest request, String conversationId) {
        conversationHistoryService.get(conversationId, RequestIdentity.requiredUserId(request));
    }
}
