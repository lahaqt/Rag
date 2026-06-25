package com.example.ragagent.vector;

import com.example.ragagent.config.RagProperties;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(prefix = "rag.vector.embedding", name = "provider", havingValue = "siliconflow", matchIfMissing = true)
public class SiliconFlowEmbeddingClient implements EmbeddingClient {
    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final int dimensions;

    public SiliconFlowEmbeddingClient(RagProperties properties) {
        RagProperties.Embedding embedding = properties.vector().embedding();
        if (embedding.apiKey().isBlank()) {
            throw new IllegalStateException("rag.vector.embedding.api-key is required when provider is siliconflow.");
        }
        String baseUrl = embedding.baseUrl().isBlank() ? "https://api.siliconflow.cn/v1" : embedding.baseUrl();
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = embedding.apiKey();
        this.model = embedding.model();
        this.dimensions = embedding.dimensions();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        SiliconFlowEmbeddingResponse response = restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(new SiliconFlowEmbeddingRequest(model, texts, "float", dimensions, "right"))
                .retrieve()
                .body(SiliconFlowEmbeddingResponse.class);
        if (response == null || response.data() == null || response.data().size() != texts.size()) {
            throw new IllegalStateException("SiliconFlow embedding API returned an unexpected response.");
        }
        return response.data().stream()
                .sorted(java.util.Comparator.comparingInt(SiliconFlowEmbeddingData::index))
                .map(SiliconFlowEmbeddingData::embedding)
                .map(this::toFloatArray)
                .toList();
    }

    @Override
    public String providerName() {
        return "siliconflow";
    }

    private float[] toFloatArray(List<Double> values) {
        float[] vector = new float[values.size()];
        for (int index = 0; index < values.size(); index++) {
            vector[index] = values.get(index).floatValue();
        }
        return vector;
    }

    private record SiliconFlowEmbeddingRequest(
            String model,
            List<String> input,
            String encoding_format,
            int dimensions,
            String truncate
    ) {
    }

    private record SiliconFlowEmbeddingResponse(List<SiliconFlowEmbeddingData> data) {
    }

    private record SiliconFlowEmbeddingData(List<Double> embedding, int index) {
    }
}
