package com.example.ragagent.client;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.service.QueryAnalysisClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpQueryAnalysisClient implements QueryAnalysisClient {
    private final RestClient restClient;

    public HttpQueryAnalysisClient(RagProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.downstream().queryRewriteBaseUrl())
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
}
