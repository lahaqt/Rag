package com.example.authoringcoach.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.authoringcoach.config.AuthoringProperties;
import com.example.authoringcoach.retrieval.RetrievalQueryPlan.QueryKind;
import com.example.authoringcoach.service.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmRetrievalQueryPlannerTests {
    @Test
    void buildsBoundedMultiQueryAndHydePlan() {
        LlmGateway llm = configured("""
                ```json
                {"queries":["beam bending stress", "neutral axis moment", "beam bending stress", "ignored"],
                 "hypotheticalPassage":"Bending stress varies with moment and distance from the neutral axis."}
                ```
                """);
        var settings = new AuthoringProperties.Retrieval(true, true, 3, 200);

        RetrievalQueryPlan plan = new LlmRetrievalQueryPlanner(llm, new ObjectMapper(), settings)
                .plan("How does a beam bend?", List.of("Explain bending stress"));

        assertThat(plan.variants()).extracting(RetrievalQueryPlan.QueryVariant::kind)
                .containsExactly(QueryKind.ORIGINAL, QueryKind.MULTI_QUERY, QueryKind.MULTI_QUERY, QueryKind.HYDE);
        assertThat(plan.variants()).extracting(RetrievalQueryPlan.QueryVariant::text)
                .contains("beam bending stress", "neutral axis moment")
                .doesNotContain("ignored");
    }

    @Test
    void fallsBackToOriginalQueryWhenLlmOutputIsInvalid() {
        RetrievalQueryPlan plan = new LlmRetrievalQueryPlanner(configured("not-json"), new ObjectMapper(),
                new AuthoringProperties.Retrieval(true, true, 3, 700))
                .plan("original engineering query", List.of());

        assertThat(plan.variants()).singleElement().satisfies(variant -> {
            assertThat(variant.kind()).isEqualTo(QueryKind.ORIGINAL);
            assertThat(variant.text()).isEqualTo("original engineering query");
        });
    }

    private LlmGateway configured(String response) {
        return new LlmGateway() {
            @Override public boolean isConfigured() { return true; }
            @Override public String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
                return response;
            }
        };
    }
}
