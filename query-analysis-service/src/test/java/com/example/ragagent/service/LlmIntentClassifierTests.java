package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LlmIntentClassifierTests {
    private final LlmIntentClassifier classifier = new LlmIntentClassifier(null, new ObjectMapper());

    @Test
    void parsesJsonObjectFromMarkdownFence() {
        Optional<LlmIntentClassification> classification = classifier.parse("""
                ```json
                {
                  "intent": "tool",
                  "confidence": 0.91,
                  "requestType": "TOOL_REQUEST",
                  "executionMode": "SINGLE_TOOL",
                  "requiredCapabilities": ["web_search", "unknown"],
                  "clarificationQuestion": "",
                  "slots": {"city": "北京"},
                  "systemCommand": "",
                  "reasons": ["latest_weather"]
                }
                ```
                """);

        assertThat(classification).isPresent();
        assertThat(classification.get().intent()).isEqualTo(QueryIntent.TOOL);
        assertThat(classification.get().confidence()).isEqualTo(0.91);
        assertThat(classification.get().requestType()).isEqualTo("TOOL_REQUEST");
        assertThat(classification.get().requiredCapabilities()).containsExactly("web_search");
        assertThat(classification.get().slots()).containsEntry("city", "北京");
    }

    @Test
    void rejectsUnknownIntentAndFallsBackToEmpty() {
        Optional<LlmIntentClassification> classification = classifier.parse("""
                {"intent":"unknown","confidence":0.8}
                """);

        assertThat(classification).isEmpty();
    }
}
