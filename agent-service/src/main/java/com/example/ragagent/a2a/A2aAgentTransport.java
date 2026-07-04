package com.example.ragagent.a2a;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.service.SpecialistAgent;
import com.example.ragagent.service.SpecialistAgentResult;
import java.time.Duration;

public interface A2aAgentTransport {
    boolean supports(String agentName);

    String name();

    SpecialistAgentResult execute(
            SpecialistAgent targetAgent,
            A2aMessage message,
            ChatRequest request,
            QueryAnalysisResponse analysis,
            int startStep,
            Duration timeout
    );
}
