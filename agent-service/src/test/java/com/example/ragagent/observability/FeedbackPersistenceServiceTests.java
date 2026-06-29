package com.example.ragagent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.dto.FeedbackRecord;
import com.example.ragagent.dto.FeedbackRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class FeedbackPersistenceServiceTests {
    @Test
    void savesAndListsFeedbackContext() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("feedback-test;MODE=PostgreSQL")
                .build();
        try {
            FeedbackPersistenceService service = new FeedbackPersistenceService(new JdbcTemplate(database));

            service.save(new FeedbackRequest(
                    "conversation-1",
                    42L,
                    "trace-1",
                    "up",
                    "useful",
                    "question",
                    "answer",
                    "kb-1",
                    "[\"source\"]"
            ));

            List<FeedbackRecord> records = service.listByConversation("conversation-1", 10);

            assertThat(records).hasSize(1);
            assertThat(records.get(0).rating()).isEqualTo("up");
            assertThat(records.get(0).question()).isEqualTo("question");
            assertThat(records.get(0).answer()).isEqualTo("answer");
            assertThat(records.get(0).knowledgeBaseId()).isEqualTo("kb-1");
            assertThat(records.get(0).createdAt()).isNotNull();
        } finally {
            database.shutdown();
        }
    }

    @Test
    void secondSaveWithSameConversationAndMessageUpdatesRatingInsteadOfDuplicating() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("feedback-upsert-test;MODE=PostgreSQL")
                .build();
        try {
            FeedbackPersistenceService service = new FeedbackPersistenceService(new JdbcTemplate(database));

            FeedbackRecord first = service.save(new FeedbackRequest(
                    "conversation-1",
                    7L,
                    "trace-1",
                    "up",
                    "",
                    "question",
                    "answer",
                    "kb-1",
                    "[\"source\"]"
            ));
            FeedbackRecord second = service.save(new FeedbackRequest(
                    "conversation-1",
                    7L,
                    "trace-2",
                    "down",
                    "wrong answer",
                    "question-2",
                    "answer-2",
                    "kb-1",
                    "[]"
            ));

            List<FeedbackRecord> records = service.listByConversation("conversation-1", 10);

            assertThat(records).hasSize(1);
            assertThat(records.get(0).rating()).isEqualTo("down");
            assertThat(records.get(0).traceId()).isEqualTo("trace-2");
            assertThat(records.get(0).question()).isEqualTo("question-2");
            assertThat(records.get(0).comment()).isEqualTo("wrong answer");
            assertThat(second.id()).isEqualTo(first.id());
        } finally {
            database.shutdown();
        }
    }
}
