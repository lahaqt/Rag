package com.example.authoringcoach.retrieval;

import com.example.authoringcoach.config.AuthoringProperties;
import com.example.authoringcoach.retrieval.CrossEncoderReranker.RerankDocument;
import com.example.authoringcoach.retrieval.CrossEncoderReranker.RerankScore;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Adapter for Cohere/Jina-compatible cross-encoder rerank endpoints. */
public final class HttpCrossEncoderReranker implements CrossEncoderReranker {
    private final AuthoringProperties.Reranker settings;
    private final RestClient client;

    public HttpCrossEncoderReranker(AuthoringProperties.Reranker settings, RestClient.Builder builder) {
        this.settings = settings;
        Duration timeout = Duration.ofSeconds(settings.timeoutSeconds());
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        RestClient.Builder configured = builder.clone().baseUrl(settings.baseUrl()).requestFactory(factory);
        if (!settings.bearerToken().isBlank()) {
            configured.defaultHeader("Authorization", "Bearer " + settings.bearerToken());
        }
        this.client = configured.build();
    }

    @Override
    public boolean enabled() {
        return settings.enabled() && !settings.baseUrl().isBlank();
    }

    @Override
    public List<RerankScore> rerank(String query, List<RerankDocument> documents) {
        if (!enabled() || documents == null || documents.isEmpty()) return List.of();
        JsonNode response = client.post().uri(settings.path()).contentType(MediaType.APPLICATION_JSON)
                .body(new Request(settings.model(), query, documents.stream().map(RerankDocument::text).toList(),
                        documents.size(), false))
                .retrieve().body(JsonNode.class);
        JsonNode results = response == null ? null : response.path("results");
        if (results == null || !results.isArray()) results = response == null ? null : response.path("data");
        if (results == null || !results.isArray()) throw new IllegalStateException("Reranker response has no results array");
        List<RerankScore> scores = new ArrayList<>();
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            JsonNode score = item.has("relevance_score") ? item.path("relevance_score") : item.path("score");
            if (index < 0 || index >= documents.size() || !score.isNumber()) continue;
            scores.add(new RerankScore(documents.get(index).id(), clamp(score.asDouble())));
        }
        if (scores.isEmpty()) throw new IllegalStateException("Reranker response contains no usable scores");
        return List.copyOf(scores);
    }

    private double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }

    private record Request(String model, String query, List<String> documents, int top_n,
                           boolean return_documents) { }
}
