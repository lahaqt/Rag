package com.example.ragagent.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VectorSearchRequestTests {
    @Test
    void clampsCallerControlledResultCountToSafeRange() {
        assertThat(request(10_000).normalizedTopK()).isEqualTo(20);
        assertThat(request(0).normalizedTopK()).isEqualTo(1);
        assertThat(request(null).normalizedTopK()).isEqualTo(6);
    }

    private VectorSearchRequest request(Integer topK) {
        return new VectorSearchRequest("kb", "query", topK, null, null, null, null);
    }
}
