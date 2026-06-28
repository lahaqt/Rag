package com.example.ragagent.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateKnowledgeBaseRequest(
        @NotBlank(message = "name must not be blank")
        String name,
        String description
) {
}
