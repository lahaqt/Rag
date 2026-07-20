package com.example.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.service.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HybridLongTermMemoryExtractorTests {

    @Test
    void ruleExtractorCreatesAllSixTypesWithGovernedScopes() {
        BusinessLongTermMemoryExtractor extractor = new BusinessLongTermMemoryExtractor();

        MemoryItem preference = only(extractor, "user-1", "I prefer concise answers", Map.of());
        MemoryItem fact = only(extractor, "user-1", "My environment is Windows 11", Map.of());
        MemoryItem goal = only(extractor, "user-1", "My goal is ship the service", Map.of());
        MemoryItem decision = only(extractor, "user-1", "We decided to use PostgreSQL", Map.of());
        List<MemoryItem> state = extractor.extractMemories(
                "user-1",
                "conversation-1",
                request("Continue"),
                null,
                null,
                Map.of("topicEntity", "memory routing", "orderId", "ORDER-123")
        );

        assertThat(preference.type()).isEqualTo(MemoryTypes.PREFERENCE);
        assertThat(preference.scope()).isEqualTo("user");
        assertThat(preference.metadata()).containsEntry("status", "candidate")
                .containsEntry("profileKey", "response_style");
        assertThat(fact.type()).isEqualTo(MemoryTypes.FACT);
        assertThat(fact.scope()).isEqualTo("user");
        assertThat(fact.metadata()).containsEntry("status", "candidate");
        assertThat(goal.type()).isEqualTo(MemoryTypes.GOAL);
        assertThat(goal.scope()).isEqualTo("conversation");
        assertThat(goal.metadata()).containsEntry("status", "confirmed");
        assertThat(decision.type()).isEqualTo(MemoryTypes.DECISION);
        assertThat(decision.scope()).isEqualTo("conversation");
        assertThat(state).extracting(MemoryItem::type)
                .containsExactlyInAnyOrder(MemoryTypes.TOPIC, MemoryTypes.BUSINESS_CONTEXT);
    }

    @Test
    void anonymousStableMemoryIsConfirmedOnlyInsideTheConversation() {
        MemoryItem fact = only(new BusinessLongTermMemoryExtractor(), "", "I am a Java developer", Map.of());

        assertThat(fact.type()).isEqualTo(MemoryTypes.FACT);
        assertThat(fact.scope()).isEqualTo("conversation");
        assertThat(fact.ownerId()).isEqualTo("conversation-1");
        assertThat(fact.metadata()).containsEntry("status", "confirmed");
    }

    @Test
    void rejectsSensitiveRuleContent() {
        List<MemoryItem> memories = new BusinessLongTermMemoryExtractor().extractMemories(
                "user-1",
                "conversation-1",
                request("My environment is api_key=sk-secret-value"),
                null,
                null,
                Map.of()
        );

        assertThat(memories).isEmpty();
    }

    @Test
    void llmSupplementsOnlyMissingTypesAndCapsTheTurn() {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyString(), anyString(), eq(0.0), eq(600))).thenReturn("""
                {"memories":[
                  {"type":"goal","content":"duplicate rule goal","confidence":0.9},
                  {"type":"fact","content":"The project runs on Java 17","confidence":0.9},
                  {"type":"decision","content":"Use PostgreSQL","confidence":0.85},
                  {"type":"preference","content":"Use Markdown tables","confidence":0.8}
                ]}
                """);
        HybridLongTermMemoryExtractor extractor = hybrid(llm);

        List<MemoryItem> memories = extractor.extractMemories(
                "user-1",
                "conversation-1",
                request("My goal is ship the service"),
                null,
                null,
                Map.of()
        );

        assertThat(memories).hasSize(3);
        assertThat(memories).extracting(MemoryItem::type)
                .containsExactly(MemoryTypes.GOAL, MemoryTypes.FACT, MemoryTypes.DECISION);
        assertThat(memories.get(0).metadata()).containsEntry("extractor", "rule");
        assertThat(memories.get(1).metadata()).containsEntry("extractor", "llm");
    }

    @Test
    void invalidLlmOutputFallsBackToRuleResults() {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyString(), anyString(), eq(0.0), eq(600))).thenReturn("not-json");

        List<MemoryItem> memories = hybrid(llm).extractMemories(
                "user-1",
                "conversation-1",
                request("We decided to use PostgreSQL"),
                null,
                null,
                Map.of()
        );

        assertThat(memories).singleElement().satisfies(memory -> {
            assertThat(memory.type()).isEqualTo(MemoryTypes.DECISION);
            assertThat(memory.metadata()).containsEntry("extractor", "rule");
        });
    }

    @Test
    void filtersSensitiveLlmOutput() {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyString(), anyString(), eq(0.0), eq(600))).thenReturn("""
                {"memories":[{"type":"fact","content":"api_key=sk-secret-value","confidence":0.95}]}
                """);

        assertThat(hybrid(llm).extractMemories(
                "user-1", "conversation-1", request("Remember my setup"), null, null, Map.of()
        )).isEmpty();
    }

    @Test
    void llmFailureDoesNotDiscardRuleMemories() {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyString(), anyString(), eq(0.0), eq(600)))
                .thenThrow(new IllegalStateException("provider unavailable"));

        List<MemoryItem> memories = hybrid(llm).extractMemories(
                "user-1", "conversation-1", request("My goal is ship safely"), null, null, Map.of()
        );

        assertThat(memories).singleElement()
                .extracting(MemoryItem::type)
                .isEqualTo(MemoryTypes.GOAL);
    }

    private HybridLongTermMemoryExtractor hybrid(LlmGateway llm) {
        RagProperties properties = new RagProperties(null, null, null, null, null, null, null, null, null, null);
        return new HybridLongTermMemoryExtractor(properties.memory(), llm, new ObjectMapper());
    }

    private MemoryItem only(
            BusinessLongTermMemoryExtractor extractor,
            String userId,
            String query,
            Map<String, String> state
    ) {
        return extractor.extractMemories(
                userId, "conversation-1", request(query), null, null, state
        ).get(0);
    }

    private ChatRequest request(String query) {
        return new ChatRequest(query, "kb-1", "conversation-1", List.of(), null);
    }
}
