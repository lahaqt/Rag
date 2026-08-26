package com.example.authoringcoach.service;

import com.example.authoringcoach.dto.VectorSearchRequest;
import com.example.authoringcoach.dto.VectorSearchResponse;

public interface StorageRetrievalClient {
    VectorSearchResponse search(VectorSearchRequest request);
}
