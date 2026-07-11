package com.example.ragagent.client;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.observability.TracePropagationInterceptor;
import com.example.ragagent.service.QueryAnalysisClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpQueryAnalysisClient implements QueryAnalysisClient {
    private final RestClient restClient;

    public HttpQueryAnalysisClient(
            RagProperties properties,
            RestClient.Builder restClientBuilder,
            TracePropagationInterceptor tracePropagationInterceptor
    ) {
        this.restClient = restClientBuilder.clone()
                .baseUrl(properties.downstream().queryRewriteBaseUrl())
                .requestFactory(requestFactory(properties.downstream().queryAnalysisTimeoutSeconds()))
                .requestInterceptor(tracePropagationInterceptor)
                .build();
    }

    @Override
    public QueryAnalysisResponse analyze(ChatRequest request) {
        QueryAnalysisRequest body = new QueryAnalysisRequest(
                request.query(),
                request.knowledgeBaseId(),
                request.conversationId(),
                request.normalizedHistory()
        );

        return restClient.post()
                .uri("/api/chat/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(QueryAnalysisResponse.class);
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
