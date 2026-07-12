package com.example.ragagent.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.VectorSearchMatch;
import com.example.ragagent.observability.TracePropagationInterceptor;
import com.example.ragagent.service.Reranker;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SiliconFlowRerankerClient implements Reranker {
    private static final Logger log = LoggerFactory.getLogger(SiliconFlowRerankerClient.class);

    private final RestClient restClient;
    private final RagProperties.Reranker config;

    public SiliconFlowRerankerClient(
            RagProperties properties,
            RestClient.Builder restClientBuilder,
            TracePropagationInterceptor tracePropagationInterceptor
    ) {
        this.config = properties.retrieval().reranker();
        this.restClient = restClientBuilder.clone()
                .baseUrl(config.baseUrl())
                .requestFactory(requestFactory(config.timeoutSeconds()))
                .requestInterceptor(tracePropagationInterceptor)
                .build();
        log.info("Reranker configured provider=siliconflow enabled={} baseUrl={} model={} apiKeyPresent={}",
                config.enabled(), config.baseUrl(), config.model(), !config.apiKey().isBlank());
    }

    @Override
    public List<VectorSearchMatch> rerank(String query, List<VectorSearchMatch> candidates, int topK) {
        if (!config.enabled() || config.apiKey().isBlank() || candidates.isEmpty()) {
            return candidates;
        }

        try {
            List<String> documents = candidates.stream().map(VectorSearchMatch::content).toList();
            SiliconFlowRerankResponse response = restClient.post()
                    .uri("/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + config.apiKey())
                    .body(new SiliconFlowRerankRequest(
                            config.model(),
                            query,
                            documents,
                            config.instruction(),
                            Math.min(topK, candidates.size()),
                            false
                    ))
                    .retrieve()
                    .body(SiliconFlowRerankResponse.class);
            if (response == null || response.results() == null || response.results().isEmpty()) {
                log.warn("SiliconFlow reranker returned no results; keeping retrieval order.");
                return candidates;
            }

            List<VectorSearchMatch> reranked = response.results().stream()
                    .filter(result -> result.index() != null && result.index() >= 0 && result.index() < candidates.size())
                    .filter(result -> result.relevanceScore() != null)
                    .sorted(Comparator.comparing(SiliconFlowRerankResult::relevanceScore).reversed())
                    .map(result -> withRerankScore(candidates.get(result.index()), result.relevanceScore()))
                    .toList();
            return reranked.isEmpty() ? candidates : reranked;
        } catch (Exception exception) {
            log.warn("SiliconFlow reranker failed; keeping retrieval order. error={}", exception.getMessage());
            return candidates;
        }
    }

    private VectorSearchMatch withRerankScore(VectorSearchMatch match, double score) {
        return new VectorSearchMatch(
                match.knowledgeBaseId(),
                match.documentId(),
                match.chunkId(),
                match.chunkIndex(),
                match.documentName(),
                match.content(),
                score
        );
    }

    private JdkClientHttpRequestFactory requestFactory(int timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build()
        );
        factory.setReadTimeout(timeout);
        return factory;
    }

    private record SiliconFlowRerankRequest(
            String model,
            String query,
            List<String> documents,
            String instruction,
            int top_n,
            boolean return_documents
    ) {
    }

    private record SiliconFlowRerankResponse(List<SiliconFlowRerankResult> results) {
    }

    private record SiliconFlowRerankResult(
            Integer index,
            @JsonProperty("relevance_score") Double relevanceScore
    ) {
    }
}
