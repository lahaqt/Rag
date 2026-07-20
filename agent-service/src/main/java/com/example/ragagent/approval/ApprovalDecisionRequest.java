package com.example.ragagent.approval;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record ApprovalDecisionRequest(
        @NotNull ApprovalDecision decision,
        Map<String, Object> editedArguments,
        String comment,
        Integer version
) {
}
