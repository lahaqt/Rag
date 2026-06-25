package com.example.ragagent.controller;

import com.example.ragagent.dto.VectorIndexStatusResponse;
import com.example.ragagent.dto.VectorSearchMatchResponse;
import com.example.ragagent.dto.VectorSearchRequest;
import com.example.ragagent.dto.VectorSearchResponse;
import com.example.ragagent.vector.VectorIndexService;
import com.example.ragagent.vector.VectorIndexStatus;
import com.example.ragagent.vector.VectorSearchMatch;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vector")
public class VectorController {
    private final VectorIndexService vectorIndexService;

    public VectorController(VectorIndexService vectorIndexService) {
        this.vectorIndexService = vectorIndexService;
    }

    @PostMapping("/search")
    public VectorSearchResponse search(@Valid @RequestBody VectorSearchRequest request) {
        return new VectorSearchResponse(vectorIndexService.search(
                        request.knowledgeBaseId(),
                        request.query(),
                        request.normalizedTopK(),
                        request.normalizedSimilarityThreshold(),
                        request.normalizedRetrievalMode(),
                        request.normalizedQueryExpansionEnabled(),
                        request.normalizedQueryExpansionCount()
                ).stream()
                .map(this::toResponse)
                .toList());
    }

    @GetMapping("/status")
    public VectorIndexStatusResponse status() {
        return toResponse(vectorIndexService.status());
    }

    private VectorSearchMatchResponse toResponse(VectorSearchMatch match) {
        return new VectorSearchMatchResponse(
                match.knowledgeBaseId(),
                match.documentId(),
                match.chunkId(),
                match.chunkIndex(),
                match.documentName(),
                match.content(),
                match.score()
        );
    }

    private VectorIndexStatusResponse toResponse(VectorIndexStatus status) {
        return new VectorIndexStatusResponse(
                status.storeProvider(),
                status.collection(),
                status.connectionUrl(),
                status.embeddingProvider(),
                status.vectorCount(),
                status.documentCount(),
                status.lastIndexedAt()
        );
    }
}
