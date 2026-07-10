package com.example.ragagent.a2a;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentProvider;
import io.a2a.spec.AgentSkill;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Agent-card registry for the nodes of the Spring AI Alibaba graph. */
@Component
public class A2aAgentRegistry {
    private final Map<String, AgentCard> cards;

    public A2aAgentRegistry() {
        Map<String, AgentCard> nextCards = new LinkedHashMap<>();
        nextCards.put("knowledge", specialist(
                "knowledge",
                "Knowledge Agent",
                "Retrieves business-operation knowledge-base evidence.",
                "knowledge_retrieval",
                List.of("rag", "knowledge", "business-operation")
        ));
        nextCards.put("web_search", specialist(
                "web_search",
                "Web Search Agent",
                "Searches current external information for realtime questions.",
                "web_search",
                List.of("web", "realtime", "search")
        ));
        nextCards.put("mcp_tool", specialist(
                "mcp_tool",
                "MCP Tool Agent",
                "Selects and invokes connected Model Context Protocol tools.",
                "mcp_tool_call",
                List.of("mcp", "tool", "enterprise-api")
        ));
        nextCards.put("follow_up", specialist(
                "follow_up",
                "Follow-up Agent",
                "Requests missing business context for ambiguous questions.",
                "ask_follow_up",
                List.of("follow-up", "clarification")
        ));
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
                .name("RAG Spring AI Alibaba Agent")
                .description("Runs ordinary and multi-agent chat through Spring AI Alibaba graph runtimes.")
                .url(url)
                .provider(new AgentProvider("rag-agent-service", "http://127.0.0.1:28083"))
                .version("0.2.0")
                .documentationUrl("/api/chat/multi-agent")
                .capabilities(new AgentCapabilities(true, false, true, List.of()))
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain", "application/json"))
                .skills(cards().stream().flatMap(card -> card.skills().stream()).toList())
                .supportsAuthenticatedExtendedCard(false)
                .securitySchemes(Map.of())
                .security(List.of())
                .additionalInterfaces(List.of())
                .preferredTransport("JSONRPC")
                .protocolVersion("0.3.0")
                .build();
    }

    private AgentCard specialist(
            String id,
            String name,
            String description,
            String skillId,
            List<String> tags
    ) {
        AgentSkill skill = new AgentSkill.Builder()
                .id(skillId)
                .name(name)
                .description(description)
                .tags(tags)
                .examples(List.of())
                .inputModes(List.of("text/plain"))
                .outputModes(List.of("text/plain", "application/json"))
                .build();
        return A2aAgentCards.specialist(id, name, description, skill);
    }
}
