package com.example.ragagent.service;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;

public interface QueryAnalysisClient {
    QueryAnalysisResponse analyze(ChatRequest request);
}
