package com.example.authoringcoach.retrieval;

import com.example.authoringcoach.dto.VectorSearchRequest;
import com.example.authoringcoach.dto.VectorSearchResponse;

@FunctionalInterface
public interface CourseSearchGateway {
    VectorSearchResponse search(VectorSearchRequest request);
}
