package com.example.ragagent.controller;

import com.example.ragagent.memory.MemoryForgetResult;
import com.example.ragagent.memory.MemoryGovernanceService;
import com.example.ragagent.memory.MemoryItem;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;

/** User-facing erasure endpoints for derived and long-term memory. */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {
    private final MemoryGovernanceService memoryGovernanceService;

    public MemoryController(MemoryGovernanceService memoryGovernanceService) {
        this.memoryGovernanceService = memoryGovernanceService;
    }

    @DeleteMapping("/conversations/{conversationId}")
    public MemoryForgetResult forgetConversation(
            @PathVariable String conversationId,
            @RequestParam String userId
    ) {
        return memoryGovernanceService.forgetConversation(userId, conversationId);
    }

    @DeleteMapping("/users/{userId}")
    public MemoryForgetResult forgetUser(@PathVariable String userId) {
        return memoryGovernanceService.forgetUser(userId);
    }

    @GetMapping("/candidates")
    public List<MemoryItem> candidates(
            @RequestParam String userId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return memoryGovernanceService.listCandidates(userId, limit);
    }

    @PostMapping("/candidates/{memoryId}/confirm")
    public MemoryItem confirmCandidate(@PathVariable String memoryId, @RequestParam String userId) {
        return memoryGovernanceService.confirmCandidate(userId, memoryId);
    }

    @DeleteMapping("/candidates/{memoryId}")
    public void rejectCandidate(@PathVariable String memoryId, @RequestParam String userId) {
        memoryGovernanceService.rejectCandidate(userId, memoryId);
    }
}
