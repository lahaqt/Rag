package com.example.ragagent.a2a;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.service.SpecialistAgent;
import com.example.ragagent.service.SpecialistAgentResult;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class LocalA2aAgentTransport implements A2aAgentTransport {
    @Override
    public boolean supports(String agentName) {
        return true;
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public SpecialistAgentResult execute(
            SpecialistAgent targetAgent,
            A2aMessage message,
            ChatRequest request,
            QueryAnalysisResponse analysis,
            int startStep,
            Duration timeout
    ) {
        return targetAgent.run(request, analysis, startStep);
    }
}
