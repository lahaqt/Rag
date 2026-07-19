package com.example.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultMemoryRecallPolicyTests {
    private final DefaultMemoryRecallPolicy policy = new DefaultMemoryRecallPolicy();

    @Test
    void usesRewrittenQueryForMeaningfulRequests() {
        MemoryRecallDecision decision = policy.decide(
                request("那个方案现在怎么实现"),
                analysis("那个方案现在怎么实现", "实现之前确定的长期记忆召回方案", "USER_QUESTION", "knowledge")
        );

        assertThat(decision.shouldRecall()).isTrue();
        assertThat(decision.query()).isEqualTo("实现之前确定的长期记忆召回方案");
    }

    @Test
    void skipsChitchatAndTrivialAcknowledgements() {
        MemoryRecallDecision chitchat = policy.decide(
                request("你好"),
                analysis("你好", "你好", "CHITCHAT", "chitchat")
        );
        MemoryRecallDecision acknowledgement = policy.decide(
                request("好的"),
                analysis("好的", "好的", "USER_QUESTION", "direct")
        );

        assertThat(chitchat.shouldRecall()).isFalse();
        assertThat(chitchat.reason()).isEqualTo("chitchat");
        assertThat(acknowledgement.shouldRecall()).isFalse();
        assertThat(acknowledgement.reason()).isEqualTo("trivial_query");
    }

    @Test
    void explicitHistoryReferenceOverridesChitchatClassification() {
        MemoryRecallDecision decision = policy.decide(
                request("继续上次的方案"),
                analysis("继续上次的方案", "继续上次的方案", "CHITCHAT", "chitchat")
        );

        assertThat(decision.shouldRecall()).isTrue();
        assertThat(decision.reason()).isEqualTo("explicit_history_reference");
    }

    private ChatRequest request(String query) {
        return new ChatRequest(query, "kb-1", "conversation-1", List.of(), null);
    }

    private QueryAnalysisResponse analysis(String original, String rewritten, String requestType, String intent) {
        return new QueryAnalysisResponse(
                "conversation-1",
                "kb-1",
                original,
                original,
                rewritten,
                intent,
                0.9,
                "direct",
                !original.equals(rewritten),
                !original.equals(rewritten),
                0,
                List.of(rewritten),
                requestType,
                "DIRECT",
                List.of(),
                "",
                Map.of(),
                "",
                List.of("test")
        );
    }
}
