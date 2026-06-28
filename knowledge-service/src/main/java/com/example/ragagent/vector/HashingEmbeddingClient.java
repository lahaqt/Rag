package com.example.ragagent.vector;

import com.example.ragagent.config.RagProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rag.vector.embedding", name = "provider", havingValue = "hash")
public class HashingEmbeddingClient implements EmbeddingClient {
    private final int dimensions;

    public HashingEmbeddingClient(RagProperties properties) {
        this.dimensions = properties.vector().embedding().dimensions();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return texts.stream().map(this::embedText).toList();
    }

    @Override
    public String providerName() {
        return "hash";
    }

    private float[] embedText(String text) {
        float[] vector = new float[dimensions];
        if (text == null || text.isBlank()) {
            return vector;
        }

        String normalized = text.toLowerCase().replaceAll("\\s+", " ").trim();
        for (String token : normalized.split(" ")) {
            if (!token.isBlank()) {
                addFeature(vector, token);
            }
        }
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < bytes.length - 2; index++) {
            addFeature(vector, new String(bytes, index, 3, StandardCharsets.UTF_8));
        }
        normalize(vector);
        return vector;
    }

    private void addFeature(float[] vector, String feature) {
        int hash = feature.hashCode();
        int index = Math.floorMod(hash, vector.length);
        vector[index] += (hash & 1) == 0 ? 1.0f : -1.0f;
    }

    private void normalize(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum == 0.0) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int index = 0; index < vector.length; index++) {
            vector[index] = vector[index] / norm;
        }
    }
}
