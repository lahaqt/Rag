package com.example.ragagent.dto;

import java.util.List;

public record VectorSearchResponse(List<VectorSearchMatchResponse> matches) {
}
