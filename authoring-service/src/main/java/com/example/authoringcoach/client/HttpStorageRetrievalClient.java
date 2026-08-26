package com.example.authoringcoach.client;

import com.example.authoringcoach.config.AuthoringProperties;
import com.example.authoringcoach.dto.VectorSearchRequest;
import com.example.authoringcoach.dto.VectorSearchResponse;
import com.example.authoringcoach.observability.TracePropagationInterceptor;
import com.example.authoringcoach.service.StorageRetrievalClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpStorageRetrievalClient implements StorageRetrievalClient {
    private final RestClient restClient;

    public HttpStorageRetrievalClient(
            AuthoringProperties properties,
            RestClient.Builder restClientBuilder,
            TracePropagationInterceptor tracePropagationInterceptor,
            ServiceAccessTokenProvider tokens
    ) {
        this.restClient = restClientBuilder.clone()
                .baseUrl(properties.downstream().contentBaseUrl())
                .requestFactory(requestFactory(properties.downstream().contentTimeoutSeconds()))
                .requestInterceptor(tracePropagationInterceptor)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.token());
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public VectorSearchResponse search(VectorSearchRequest request) {
        return restClient.post()
                .uri("/internal/v1/courses/{courseId}/search", request.courseId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SearchBody(request.query(), request.topK(), request.similarityThreshold()))
                .retrieve()
                .body(VectorSearchResponse.class);
    }

    private JdkClientHttpRequestFactory requestFactory(int timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build()
        );
        factory.setReadTimeout(timeout);
        return factory;
    }

    private record SearchBody(String query, Integer topK, Double similarityThreshold) { }
}
