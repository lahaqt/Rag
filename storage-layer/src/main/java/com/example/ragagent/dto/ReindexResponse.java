package com.example.ragagent.dto;

public record ReindexResponse(
        String documentId,
        String status,
        int chunkCount,
        String message
) {
}
