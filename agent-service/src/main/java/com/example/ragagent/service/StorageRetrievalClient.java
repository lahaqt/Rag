package com.example.ragagent.service;

import com.example.ragagent.dto.VectorSearchRequest;
import com.example.ragagent.dto.VectorSearchResponse;

public interface StorageRetrievalClient {
    VectorSearchResponse search(VectorSearchRequest request);
}
