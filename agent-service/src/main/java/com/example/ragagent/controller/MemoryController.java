package com.example.ragagent.controller;

import com.example.ragagent.memory.MemoryForgetResult;
import com.example.ragagent.memory.MemoryGovernanceService;
import com.example.ragagent.memory.MemoryItem;
import com.example.ragagent.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
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
            HttpServletRequest httpRequest,
            @PathVariable String conversationId,
            @RequestParam String userId
    ) {
        return memoryGovernanceService.forgetConversation(RequestIdentity.requireMatchingUser(httpRequest, userId), conversationId);
    }

    @DeleteMapping("/users/{userId}")
    public MemoryForgetResult forgetUser(HttpServletRequest httpRequest, @PathVariable String userId) {
        return memoryGovernanceService.forgetUser(RequestIdentity.requireMatchingUser(httpRequest, userId));
    }

    @GetMapping("/candidates")
    public List<MemoryItem> candidates(
            HttpServletRequest httpRequest,
            @RequestParam String userId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return memoryGovernanceService.listCandidates(RequestIdentity.requireMatchingUser(httpRequest, userId), limit);
    }

    @PostMapping("/candidates/{memoryId}/confirm")
    public MemoryItem confirmCandidate(HttpServletRequest httpRequest, @PathVariable String memoryId, @RequestParam String userId) {
        return memoryGovernanceService.confirmCandidate(RequestIdentity.requireMatchingUser(httpRequest, userId), memoryId);
    }

    @DeleteMapping("/candidates/{memoryId}")
    public void rejectCandidate(HttpServletRequest httpRequest, @PathVariable String memoryId, @RequestParam String userId) {
        memoryGovernanceService.rejectCandidate(RequestIdentity.requireMatchingUser(httpRequest, userId), memoryId);
    }
}
