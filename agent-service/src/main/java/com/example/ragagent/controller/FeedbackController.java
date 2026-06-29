package com.example.ragagent.controller;

import com.example.ragagent.dto.FeedbackRecord;
import com.example.ragagent.dto.FeedbackRequest;
import com.example.ragagent.observability.FeedbackPersistenceService;
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

    public FeedbackController(FeedbackPersistenceService feedbackPersistenceService) {
        this.feedbackPersistenceService = feedbackPersistenceService;
    }

    @PostMapping
    public FeedbackRecord submit(@Valid @RequestBody FeedbackRequest request) {
        return feedbackPersistenceService.save(request);
    }

    @GetMapping
    public List<FeedbackRecord> list(
            @RequestParam String conversationId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return feedbackPersistenceService.listByConversation(conversationId, limit);
    }
}