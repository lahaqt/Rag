package com.example.ragagent.memory;

import java.util.List;

public interface MemoryEmbeddingClient {
    List<float[]> embed(List<String> texts);

    default float[] embedOne(String text) {
        return embed(List.of(text)).get(0);
    }

    String providerName();

    String modelName();

    int dimensions();
}
