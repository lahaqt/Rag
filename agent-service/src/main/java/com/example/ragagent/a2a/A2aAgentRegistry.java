package com.example.ragagent.a2a;

import com.example.ragagent.service.SpecialistAgent;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class A2aAgentRegistry {
    private final Map<String, AgentCard> cards;

    public A2aAgentRegistry(List<SpecialistAgent> agents) {
        Map<String, AgentCard> nextCards = new LinkedHashMap<>();
        for (SpecialistAgent agent : agents) {
            nextCards.put(agent.name(), agent.agentCard());
        }
        this.cards = Map.copyOf(nextCards);
    }

    public List<AgentCard> cards() {
        return List.copyOf(cards.values());
    }

    public AgentCard card(String agentName) {
        return cards.get(agentName);
    }

    public AgentCard orchestratorCard(String url) {
        return new AgentCard.Builder()
                .name("RAG Multi-Agent Orchestrator")
                .description("Routes explicit /multi-agent chat requests through the Spring AI Alibaba graph runtime.")
                .url(url)
                .provider(new AgentProvider("rag-agent-service", "http://127.0.0.1:28083"))
                .version("0.1.0")
                .documentationUrl("/api/chat/multi-agent")
                .capabilities(new AgentCapabilities(true, false, true, List.of()))
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain", "application/json"))
                .skills(cards().stream()
                        .flatMap(card -> card.skills().stream())
                        .toList())
                .supportsAuthenticatedExtendedCard(false)
                .securitySchemes(Map.of())
                .security(List.of())
                .additionalInterfaces(List.of())
                .preferredTransport("JSONRPC")
                .protocolVersion("0.3.0")
                .build();
    }
}
