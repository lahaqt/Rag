package com.example.ragagent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class AgentTracePersistenceServiceTests {
    @Test
    void persistsRunAndIncrementalStepsForDiagnostics() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("agent-run-test;MODE=PostgreSQL")
                .build();
        try {
            AgentTracePersistenceService service = new AgentTracePersistenceService(
                    new JdbcTemplate(database), new ObjectMapper()
            );
            service.startRun("run-1", new ChatRequest("refund", "kb-1", "conversation-1", null, null), "rag-agent");
            service.recordRunStep("run-1", new AgentTraceStep(0, "route", "knowledge", "rag_retrieval", "select", "selected", "ok", 3, "", "", "", Map.of("executionPlan", Map.of("planId", "plan-1"))));

            AgentRunRecord run = service.findRun("run-1");
            assertThat(run.status()).isEqualTo("RUNNING");
            assertThat(run.graphName()).isEqualTo("rag-agent");
            assertThat(service.findRunSteps("run-1"))
                    .singleElement()
                    .satisfies(step -> {
                        assertThat(step.action()).isEqualTo("select");
                        assertThat(step.attributes()).containsKey("executionPlan");
                    });
        } finally {
            database.shutdown();
        }
    }
}
