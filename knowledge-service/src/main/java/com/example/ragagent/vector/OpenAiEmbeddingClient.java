package com.example.ragagent.vector;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.observability.TracePropagationInterceptor;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(prefix = "rag.vector.embedding", name = "provider", havingValue = "openai")
public class OpenAiEmbeddingClient implements EmbeddingClient {
    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public OpenAiEmbeddingClient(
            RagProperties properties,
            RestClient.Builder restClientBuilder,
            TracePropagationInterceptor tracePropagationInterceptor
    ) {
        RagProperties.Embedding embedding = properties.vector().embedding();
        if (embedding.apiKey().isBlank()) {
            throw new IllegalStateException("rag.vector.embedding.api-key is required when provider is openai.");
        }
        String baseUrl = embedding.baseUrl().isBlank() ? "https://api.openai.com/v1" : embedding.baseUrl();
        this.restClient = restClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestInterceptor(tracePropagationInterceptor)
                .build();
        this.apiKey = embedding.apiKey();
        this.model = embedding.model();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        OpenAiEmbeddingResponse response = restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(new OpenAiEmbeddingRequest(model, texts))
                .retrieve()
                .body(OpenAiEmbeddingResponse.class);
        if (response == null || response.data() == null || response.data().size() != texts.size()) {
            throw new IllegalStateException("Embedding API returned an unexpected response.");
        }
        return response.data().stream()
                .map(OpenAiEmbeddingData::embedding)
                .map(this::toFloatArray)
                .toList();
    }

    @Override
    public String providerName() {
        return "openai";
    }

    private float[] toFloatArray(List<Double> values) {
        float[] vector = new float[values.size()];
        for (int index = 0; index < values.size(); index++) {
            vector[index] = values.get(index).floatValue();
        }
        return vector;
    }

    private record OpenAiEmbeddingRequest(String model, List<String> input) {
    }

    private record OpenAiEmbeddingResponse(List<OpenAiEmbeddingData> data) {
    }

    private record OpenAiEmbeddingData(List<Double> embedding) {
    }
}
