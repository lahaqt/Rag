package com.example.ragagent.memory;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;

public interface MemoryRecallPolicy {
    MemoryRecallDecision decide(ChatRequest request, QueryAnalysisResponse analysis);
}
