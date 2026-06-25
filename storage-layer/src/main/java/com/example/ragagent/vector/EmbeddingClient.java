package com.example.ragagent.vector;

import java.util.List;

public interface EmbeddingClient {
    List<float[]> embed(List<String> texts);

    default float[] embedOne(String text) {
        return embed(List.of(text)).get(0);
    }

    String providerName();
}
