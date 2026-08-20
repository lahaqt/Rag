package com.example.ragagent.client;

import com.example.ragagent.authoring.CourseKnowledgeClient;
import com.example.ragagent.config.RagProperties;
import com.example.ragagent.observability.TracePropagationInterceptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/** Internal, signed adapter for the knowledge-service document lifecycle API. */
@Component
public class HttpCourseKnowledgeClient implements CourseKnowledgeClient {
    private static final ParameterizedTypeReference<List<KnowledgeDocument>> DOCUMENT_LIST = new ParameterizedTypeReference<>() {
    };
    private final RestClient restClient;

    public HttpCourseKnowledgeClient(
            RagProperties properties,
            RestClient.Builder restClientBuilder,
            TracePropagationInterceptor tracePropagationInterceptor,
            @Value("${rag.security.identity-signing-secret:}") String signingSecret,
            @Value("${rag.security.service-identity:agent-service}") String serviceIdentity
    ) {
        this.restClient = restClientBuilder.clone()
                .baseUrl(properties.downstream().storageBaseUrl())
                .requestInterceptor(tracePropagationInterceptor)
                .requestInterceptor((request, body, execution) -> {
                    String signature = signature(serviceIdentity, signingSecret);
                    if (signature == null) {
                        throw new IllegalStateException("rag.security.identity-signing-secret is required for knowledge-service calls");
                    }
                    request.getHeaders().set("X-Rag-User-Id", serviceIdentity);
                    request.getHeaders().set("X-Rag-Identity-Signature", signature);
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public KnowledgeBase createKnowledgeBase(String name, String description) {
        return restClient.post().uri("/api/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateKnowledgeBaseBody(name, description == null ? "" : description))
                .retrieve().body(KnowledgeBase.class);
    }

    @Override
    public List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
        List<KnowledgeDocument> documents = restClient.get().uri("/api/knowledge-bases/{id}/documents", knowledgeBaseId)
                .retrieve().body(DOCUMENT_LIST);
        return documents == null ? List.of() : documents;
    }

    @Override
    public KnowledgeDocument uploadDocument(String knowledgeBaseId, MultipartFile file) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", file.getResource()).filename(file.getOriginalFilename() == null ? "course-material" : file.getOriginalFilename());
        return restClient.post().uri("/api/knowledge-bases/{id}/documents", knowledgeBaseId)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(body.build()).retrieve().body(KnowledgeDocument.class);
    }

    @Override
    public KnowledgeDocument reparseDocument(String knowledgeBaseId, String documentId) {
        return restClient.post().uri("/api/knowledge-bases/{id}/documents/{documentId}/reparse", knowledgeBaseId, documentId)
                .retrieve().body(KnowledgeDocument.class);
    }

    @Override
    public void reindexDocument(String knowledgeBaseId, String documentId) {
        restClient.post().uri("/api/knowledge-bases/{id}/documents/{documentId}/reindex", knowledgeBaseId, documentId)
                .retrieve().toBodilessEntity();
    }

    @Override
    public void deleteDocument(String knowledgeBaseId, String documentId) {
        restClient.delete().uri("/api/knowledge-bases/{id}/documents/{documentId}", knowledgeBaseId, documentId)
                .retrieve().toBodilessEntity();
    }

    private String signature(String identity, String secret) {
        if (identity == null || identity.isBlank() || secret == null || secret.isBlank()) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign knowledge-service identity", exception);
        }
    }

    private record CreateKnowledgeBaseBody(String name, String description) {
    }
}
