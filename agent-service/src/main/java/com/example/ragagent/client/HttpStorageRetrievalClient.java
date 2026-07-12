package com.example.ragagent.client;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.VectorSearchRequest;
import com.example.ragagent.dto.VectorSearchResponse;
import com.example.ragagent.observability.TracePropagationInterceptor;
import com.example.ragagent.service.StorageRetrievalClient;
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
            RagProperties properties,
            RestClient.Builder restClientBuilder,
            TracePropagationInterceptor tracePropagationInterceptor
    ) {
        this.restClient = restClientBuilder.clone()
                .baseUrl(properties.downstream().storageBaseUrl())
                .requestFactory(requestFactory(properties.downstream().storageRetrievalTimeoutSeconds()))
                .requestInterceptor(tracePropagationInterceptor)
                .build();
    }

    @Override
    public VectorSearchResponse search(VectorSearchRequest request) {
        return restClient.post()
                .uri("/api/vector/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
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
}
