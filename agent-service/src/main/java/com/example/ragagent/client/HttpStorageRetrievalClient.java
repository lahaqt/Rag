package com.example.ragagent.client;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.VectorSearchRequest;
import com.example.ragagent.dto.VectorSearchResponse;
import com.example.ragagent.observability.TracePropagationInterceptor;
import com.example.ragagent.service.StorageRetrievalClient;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
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
            TracePropagationInterceptor tracePropagationInterceptor,
            @Value("${rag.security.identity-signing-secret:}") String signingSecret,
            @Value("${rag.security.service-identity:agent-service}") String serviceIdentity
    ) {
        this.restClient = restClientBuilder.clone()
                .baseUrl(properties.downstream().storageBaseUrl())
                .requestFactory(requestFactory(properties.downstream().storageRetrievalTimeoutSeconds()))
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

    private String signature(String identity, String signingSecret) {
        if (identity == null || identity.isBlank() || signingSecret == null || signingSecret.isBlank()) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign knowledge-service identity", exception);
        }
    }
}
