package com.example.ragagent.dto;

public record RetrievalHit(
        int index,
        String knowledgeBaseId,
        String documentId,
        String chunkId,
        int chunkIndex,
        String documentName,
        String content,
        double score
) {
    public Citation toCitation() {
        return toCitation(excerpt(content, 220));
    }

    public Citation toCitation(String excerpt) {
        return new Citation(
                index,
                knowledgeBaseId,
                documentId,
                chunkId,
                chunkIndex,
                documentName,
                score,
                excerpt == null ? "" : excerpt,
                content
        );
    }

    private static String excerpt(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}
