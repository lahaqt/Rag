package com.example.ragagent.controller;

import com.example.ragagent.dto.ChunkResponse;
import com.example.ragagent.dto.DocumentResponse;
import com.example.ragagent.dto.KnowledgeBaseResponse;
import com.example.ragagent.model.DocumentChunk;
import com.example.ragagent.model.KnowledgeBase;
import com.example.ragagent.model.KnowledgeDocument;
import java.util.List;

final class ApiMapper {
    private ApiMapper() {
    }

    static KnowledgeBaseResponse toKnowledgeBaseResponse(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseResponse(
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                knowledgeBase.getDocuments().size(),
                knowledgeBase.getChunkCount(),
                knowledgeBase.getCreatedAt(),
                knowledgeBase.getUpdatedAt()
        );
    }

    static DocumentResponse toDocumentResponse(KnowledgeDocument document) {
        return new DocumentResponse(
                document.getId(),
                document.getKnowledgeBaseId(),
                document.getFileName(),
                document.getContentType(),
                document.getSize(),
                document.getStatus().name(),
                document.getObjectKey(),
                document.getChunks().size(),
                document.getMetadata(),
                document.getErrorMessage(),
                document.getUploadedAt(),
                document.getParsedAt()
        );
    }

    static List<ChunkResponse> toChunkResponses(List<DocumentChunk> chunks) {
        return chunks.stream().map(ApiMapper::toChunkResponse).toList();
    }

    static ChunkResponse toChunkResponse(DocumentChunk chunk) {
        return new ChunkResponse(
                chunk.getId(),
                chunk.getDocumentId(),
                chunk.getDocumentName(),
                chunk.getKnowledgeBaseId(),
                chunk.getChunkIndex(),
                chunk.getContent()
        );
    }
}
