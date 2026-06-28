package com.example.ragagent.model;

public class DocumentChunk {
    private final String id;
    private final String documentId;
    private final String documentName;
    private final String knowledgeBaseId;
    private final int chunkIndex;
    private final String content;

    public DocumentChunk(
            String id,
            String documentId,
            String documentName,
            String knowledgeBaseId,
            int chunkIndex,
            String content
    ) {
        this.id = id;
        this.documentId = documentId;
        this.documentName = documentName;
        this.knowledgeBaseId = knowledgeBaseId;
        this.chunkIndex = chunkIndex;
        this.content = content;
    }

    public String getId() {
        return id;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getDocumentName() {
        return documentName;
    }

    public String getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

}
