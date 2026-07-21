package com.example.ragagent.controller;

import com.example.ragagent.approval.ApprovalDecisionRequest;
import com.example.ragagent.approval.ApprovalRequest;
import com.example.ragagent.approval.ApprovalService;
import com.example.ragagent.dto.ChatResponse;
import com.example.ragagent.service.SpringAiAlibabaAgentRuntime;
import com.example.ragagent.memory.MemoryGovernanceService;
import com.example.ragagent.memory.MemoryItem;
import com.example.ragagent.memory.MemoryTypes;
import com.example.ragagent.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {
    private final ApprovalService approvalService;
    private final SpringAiAlibabaAgentRuntime runtime;
    private final MemoryGovernanceService memoryGovernanceService;

    public ApprovalController(ApprovalService approvalService, SpringAiAlibabaAgentRuntime runtime,
                              MemoryGovernanceService memoryGovernanceService) {
        this.approvalService = approvalService;
        this.runtime = runtime;
        this.memoryGovernanceService = memoryGovernanceService;
    }

    @GetMapping
    public List<ApprovalRequest> pending(HttpServletRequest httpRequest, @RequestParam String userId, @RequestParam(defaultValue = "20") int limit) {
        userId = RequestIdentity.requireMatchingUser(httpRequest, userId);
        for (MemoryItem item : memoryGovernanceService.listCandidates(userId, 100)) {
            if (MemoryTypes.PREFERENCE.equals(item.type())) {
                approvalService.createMemoryPreferenceApproval(userId, item.conversationId(), item.id(), item.content(), new java.util.LinkedHashMap<>(item.metadata()));
            }
        }
        return approvalService.listPending(userId, limit);
    }

    @GetMapping("/{approvalId}")
    public ApprovalRequest get(HttpServletRequest httpRequest, @PathVariable String approvalId, @RequestParam String userId) {
        userId = RequestIdentity.requireMatchingUser(httpRequest, userId);
        return approvalService.get(approvalId, userId);
    }

    @PostMapping("/{approvalId}/decision")
    public Map<String, Object> decide(HttpServletRequest httpRequest, @PathVariable String approvalId, @RequestParam String userId,
                                      @Valid @RequestBody ApprovalDecisionRequest request) {
        userId = RequestIdentity.requireMatchingUser(httpRequest, userId);
        ApprovalRequest approval = approvalService.decide(approvalId, userId, request);
        if (approval.type() == com.example.ragagent.approval.ApprovalType.MEMORY_PREFERENCE) {
            String memoryId = String.valueOf(approval.arguments().get("memoryId"));
            if (approval.status() == com.example.ragagent.approval.ApprovalStatus.APPROVED) {
                memoryGovernanceService.confirmCandidate(userId, memoryId);
            } else if (approval.status() == com.example.ragagent.approval.ApprovalStatus.EDITED) {
                String content = String.valueOf(approval.editedArguments().getOrDefault("content", ""));
                memoryGovernanceService.editAndConfirmCandidate(userId, memoryId, content);
            } else if (approval.status() == com.example.ragagent.approval.ApprovalStatus.REJECTED) {
                memoryGovernanceService.rejectCandidate(userId, memoryId);
            }
        }
        ChatResponse response = approval.type() == com.example.ragagent.approval.ApprovalType.WRITE_TOOL
                && approval.status() != com.example.ragagent.approval.ApprovalStatus.REJECTED
                ? runtime.resumeApprovedWrite(approval) : null;
        return Map.of("approval", approval, "response", response == null ? Map.of() : response);
    }
}
