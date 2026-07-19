package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatAttachment;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptBuilderAttachmentsTest {
    private final PromptBuilder promptBuilder = new PromptBuilder(
            new RagProperties(null, null, null, null, null, null, null, null, null)
    );

    @Test
    void noAttachmentsProducesNoAttachmentSection() {
        ChatRequest request = new ChatRequest("hello", null, null, List.of(), null);

        String prompt = promptBuilder.userPrompt(request, directAnalysis("hello"), List.of());

        assertThat(prompt).doesNotContain("Attachments:");
    }

    @Test
    void singleAttachmentIsIncluded() {
        ChatAttachment attachment = new ChatAttachment("notes.md", "text/markdown", 7, "# Hello");
        ChatRequest request = new ChatRequest("summary", null, null, List.of(), null, List.of(attachment));

        String prompt = promptBuilder.userPrompt(request, directAnalysis("summary"), List.of());

        assertThat(prompt)
                .contains("Attachments:")
                .contains("[1] File: notes.md")
                .contains("# Hello");
    }

    @Test
    void multipleAttachmentsAreNumbered() {
        ChatAttachment first = new ChatAttachment("a.txt", "text/plain", 5, "first");
        ChatAttachment second = new ChatAttachment("b.txt", "text/plain", 6, "second");
        ChatRequest request = new ChatRequest("compare", null, null, List.of(), null, List.of(first, second));

        String prompt = promptBuilder.userPrompt(request, directAnalysis("compare"), List.of());

        assertThat(prompt)
                .contains("[1] File: a.txt")
                .contains("[2] File: b.txt");
    }

    @Test
    void emptyAttachmentContentIsSkipped() {
        ChatAttachment empty = new ChatAttachment("empty.txt", "text/plain", 0, "");
        ChatAttachment valid = new ChatAttachment("valid.txt", "text/plain", 4, "data");
        ChatRequest request = new ChatRequest("query", null, null, List.of(), null, List.of(empty, valid));

        String prompt = promptBuilder.userPrompt(request, directAnalysis("query"), List.of());

        assertThat(prompt).contains("[1] File: valid.txt");
        assertThat(prompt).doesNotContain("empty.txt");
    }

    @Test
    void longAttachmentContentIsTrimmed() {
        String content = "x ".repeat(50_000);
        ChatAttachment attachment = new ChatAttachment("big.txt", "text/plain", content.length(), content);
        ChatRequest request = new ChatRequest("query", null, null, List.of(), null, List.of(attachment));

        String prompt = promptBuilder.userPrompt(request, directAnalysis("query"), List.of());

        assertThat(prompt)
                .contains("Attachments:")
                .contains("[1] File: big.txt")
                .contains("...");
        assertThat(prompt.length()).isLessThan(content.length());
    }

    @Test
    void keepsConversationTurnSummariesInChronologicalOrder() {
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "first question"),
                new ChatMessage("assistant", "first answer"),
                new ChatMessage("conversation_turn_summary", "second turn digest"),
                new ChatMessage("user", "third question"),
                new ChatMessage("assistant", "third answer")
        );
        ChatRequest request = new ChatRequest("follow up", null, null, history, null);

        String prompt = promptBuilder.userPrompt(request, directAnalysis("follow up"), List.of());

        assertThat(prompt.indexOf("first question")).isLessThan(prompt.indexOf("second turn digest"));
        assertThat(prompt.indexOf("second turn digest")).isLessThan(prompt.indexOf("third question"));
    }

    @Test
    void trimsHistoryOnlyAtCompleteTurnBoundaries() {
        PromptBuilder constrained = new PromptBuilder(new RagProperties(
                null,
                null,
                null,
                new RagProperties.Prompt(4096, 128, 512, 0, 1024),
                null,
                null,
                null,
                null,
                null
        ));
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "old-question-" + "x".repeat(1000)),
                new ChatMessage("assistant", "old-answer-" + "y".repeat(1000)),
                new ChatMessage("user", "new-question-" + "a".repeat(1000)),
                new ChatMessage("assistant", "new-answer-" + "b".repeat(1000))
        );

        String prompt = constrained.userPrompt(
                new ChatRequest("follow up", null, null, history, null),
                directAnalysis("follow up"),
                List.of()
        );

        assertThat(prompt)
                .doesNotContain("old-question-")
                .doesNotContain("old-answer-")
                .contains("new-question-", "new-answer-");
    }

    @Test
    void rejectsAQueryThatExceedsTheHardInputBudget() {
        PromptBuilder constrained = new PromptBuilder(new RagProperties(
                null,
                null,
                null,
                new RagProperties.Prompt(4096, 128, 512, 0, 1024),
                null,
                null,
                null,
                null,
                null
        ));
        String oversizedQuery = "问".repeat(4000);

        assertThatThrownBy(() -> constrained.userPrompt(
                new ChatRequest(oversizedQuery, null, null, List.of(), null),
                directAnalysis(oversizedQuery),
                List.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context limit");
    }

    private QueryAnalysisResponse directAnalysis(String query) {
        return new QueryAnalysisResponse(
                "session",
                null,
                query,
                query,
                query,
                "direct",
                0.9,
                "DIRECT",
                false,
                false,
                0,
                List.of(),
                List.of()
        );
    }
}
