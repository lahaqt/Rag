package com.example.ragagent.a2a;

import com.example.ragagent.service.SpecialistAgent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class A2aAgentRegistry {
    private final Map<String, A2aAgentCard> cards;

    public A2aAgentRegistry(List<SpecialistAgent> agents) {
        Map<String, A2aAgentCard> nextCards = new LinkedHashMap<>();
        for (SpecialistAgent agent : agents) {
            nextCards.put(agent.name(), agent.agentCard());
        }
        this.cards = Map.copyOf(nextCards);
    }

    public List<A2aAgentCard> cards() {
        return List.copyOf(cards.values());
    }

    public A2aAgentCard card(String agentName) {
        return cards.get(agentName);
    }

    public A2aAgentCard orchestratorCard() {
        return new A2aAgentCard(
                "0.3.0",
                "rag_multi_agent",
                "RAG Multi-Agent Orchestrator",
                "Routes explicit /multi-agent chat requests through the Spring AI Alibaba graph runtime.",
                "/api/chat/multi-agent",
                "0.1.0",
                new A2aAgentCapabilities(true, false, true),
                List.of("text/plain"),
                List.of("text/plain"),
                cards().stream()
                        .flatMap(card -> card.skills().stream())
                        .toList()
        );
    }
}
