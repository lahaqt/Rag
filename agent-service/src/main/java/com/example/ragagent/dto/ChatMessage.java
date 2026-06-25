package com.example.ragagent.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatMessage(
        @NotBlank String role,
        @NotBlank String content
) {
}
