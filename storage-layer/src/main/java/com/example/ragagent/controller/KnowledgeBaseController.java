package com.example.ragagent.controller;

import com.example.ragagent.dto.CreateKnowledgeBaseRequest;
import com.example.ragagent.dto.ChunkResponse;
import com.example.ragagent.dto.DocumentResponse;
import com.example.ragagent.dto.KnowledgeBaseResponse;
import com.example.ragagent.dto.ReindexResponse;
import com.example.ragagent.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping
    public List<KnowledgeBaseResponse> listKnowledgeBases() {
        return knowledgeBaseService.listKnowledgeBases().stream()
                .map(ApiMapper::toKnowledgeBaseResponse)
                .toList();
    }

    @PostMapping
    public KnowledgeBaseResponse createKnowledgeBase(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return ApiMapper.toKnowledgeBaseResponse(knowledgeBaseService.createKnowledgeBase(request));
    }

    @GetMapping("/{id}/documents")
    public List<DocumentResponse> listDocuments(@PathVariable String id) {
        return knowledgeBaseService.listDocuments(id).stream()
                .map(ApiMapper::toDocumentResponse)
                .toList();
    }

    @PostMapping("/{id}/documents")
    public DocumentResponse uploadDocument(@PathVariable String id, @RequestPart("file") MultipartFile file) {
        return ApiMapper.toDocumentResponse(knowledgeBaseService.uploadDocument(id, file));
    }

    @GetMapping("/{id}/documents/{documentId}")
    public DocumentResponse getDocument(@PathVariable String id, @PathVariable String documentId) {
        return ApiMapper.toDocumentResponse(knowledgeBaseService.getDocument(id, documentId));
    }

    @GetMapping("/{id}/documents/{documentId}/chunks")
    public List<ChunkResponse> listChunks(@PathVariable String id, @PathVariable String documentId) {
        return ApiMapper.toChunkResponses(knowledgeBaseService.listChunks(id, documentId));
    }

    @PostMapping("/{id}/documents/{documentId}/reparse")
    public DocumentResponse reparseDocument(@PathVariable String id, @PathVariable String documentId) {
        return ApiMapper.toDocumentResponse(knowledgeBaseService.reparseDocument(id, documentId));
    }

    @PostMapping("/{id}/documents/{documentId}/reindex")
    public ReindexResponse reindexDocument(@PathVariable String id, @PathVariable String documentId) {
        var document = knowledgeBaseService.requestReindex(id, documentId);
        return new ReindexResponse(document.getId(), "REQUESTED", document.getChunks().size(),
                "Vector module was notified through VectorIndexPort.");
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    public void deleteDocument(@PathVariable String id, @PathVariable String documentId) {
        knowledgeBaseService.deleteDocument(id, documentId);
    }
}
