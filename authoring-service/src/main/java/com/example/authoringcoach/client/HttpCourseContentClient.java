package com.example.authoringcoach.client;

import com.example.authoringcoach.authoring.CourseContentClient;
import com.example.authoringcoach.config.AuthoringProperties;
import com.example.authoringcoach.observability.TracePropagationInterceptor;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/** Internal, signed adapter for the knowledge-service document lifecycle API. */
@Component
public class HttpCourseContentClient implements CourseContentClient {
    private static final ParameterizedTypeReference<List<CourseMaterial>> DOCUMENT_LIST = new ParameterizedTypeReference<>() {
    };
    private final RestClient restClient;

    public HttpCourseContentClient(
            AuthoringProperties properties,
            RestClient.Builder restClientBuilder,
            TracePropagationInterceptor tracePropagationInterceptor,
            ServiceAccessTokenProvider tokens
    ) {
        this.restClient = restClientBuilder.clone()
                .baseUrl(properties.downstream().contentBaseUrl())
                .requestInterceptor(tracePropagationInterceptor)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.token());
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public void provisionCourse(String courseId, String name, String description) {
        restClient.put().uri("/internal/v1/courses/{courseId}", courseId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ProvisionCourseBody(name, description == null ? "" : description))
                .retrieve().toBodilessEntity();
    }

    @Override
    public List<CourseMaterial> listMaterials(String courseId) {
        List<CourseMaterial> documents = restClient.get().uri("/internal/v1/courses/{courseId}/materials", courseId)
                .retrieve().body(DOCUMENT_LIST);
        return documents == null ? List.of() : documents;
    }

    @Override
    public CourseMaterial uploadMaterial(String courseId, MultipartFile file, String idempotencyKey) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", file.getResource()).filename(file.getOriginalFilename() == null ? "course-material" : file.getOriginalFilename());
        return restClient.post().uri("/internal/v1/courses/{courseId}/materials", courseId)
                .header("Idempotency-Key", idempotencyKey == null ? "" : idempotencyKey)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(body.build()).retrieve().body(CourseMaterial.class);
    }

    @Override
    public CourseMaterial retryMaterial(String courseId, String materialId) {
        return restClient.post().uri("/internal/v1/courses/{courseId}/materials/{materialId}/reparse", courseId, materialId)
                .retrieve().body(CourseMaterial.class);
    }

    @Override
    public void reindexMaterial(String courseId, String materialId) {
        restClient.post().uri("/internal/v1/courses/{courseId}/materials/{materialId}/reindex", courseId, materialId)
                .retrieve().toBodilessEntity();
    }

    @Override
    public void deleteMaterial(String courseId, String materialId) {
        restClient.delete().uri("/internal/v1/courses/{courseId}/materials/{materialId}", courseId, materialId)
                .retrieve().toBodilessEntity();
    }

    private record ProvisionCourseBody(String name, String description) {
    }
}
