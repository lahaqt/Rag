package com.example.ragagent.dto;

import java.time.Instant;
import java.util.Map;

public record DocumentResponse(
        String id,
        String knowledgeBaseId,
        String fileName,
        String contentType,
        long size,
        String status,
        String objectKey,
        int chunkCount,
        Map<String, String> metadata,
        String errorMessage,
        Instant uploadedAt,
        Instant parsedAt
) {
}
