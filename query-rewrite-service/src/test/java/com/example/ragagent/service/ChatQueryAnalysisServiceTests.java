package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatQueryRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatQueryAnalysisServiceTests {
    private final ChatQueryAnalysisService service = new ChatQueryAnalysisService(
            new IntentClassifier(),
            new QueryRewriteService()
    );

    @Test
    void rewritesPronounFollowUpWithRecentSubject() {
        ChatQueryAnalysis analysis = service.analyze(new ChatQueryRequest(
                "那它的保修期呢？",
                "kb-1",
                "session-1",
                List.of(
                        new ChatMessage("user", "iPhone 16 Pro 的退货政策是什么？"),
                        new ChatMessage("assistant", "iPhone 16 Pro 拆封后不支持七天无理由退货。")
                )
        ));

        assertThat(analysis.intent()).isEqualTo(QueryIntent.KNOWLEDGE);
        assertThat(analysis.rewritten()).isTrue();
        assertThat(analysis.rewrittenQuery()).contains("iPhone 16 Pro").contains("保修期");
        assertThat(analysis.route()).isEqualTo("knowledge_retrieval");
    }

    @Test
    void formalizesColloquialFirstTurnBusinessQuery() {
        ChatQueryAnalysis analysis = service.analyze(new ChatQueryRequest(
                "东西坏了咋整？",
                "kb-1",
                "session-1",
                List.of()
        ));

        assertThat(analysis.intent()).isEqualTo(QueryIntent.KNOWLEDGE);
        assertThat(analysis.rewrittenQuery()).isEqualTo("产品故障后的维修和报修流程");
    }

    @Test
    void routesChitchatWithoutRewrite() {
        ChatQueryAnalysis analysis = service.analyze(new ChatQueryRequest(
                "你好",
                "kb-1",
                "session-1",
                List.of()
        ));

        assertThat(analysis.intent()).isEqualTo(QueryIntent.CHITCHAT);
        assertThat(analysis.route()).isEqualTo("direct_reply");
        assertThat(analysis.needsRewrite()).isFalse();
    }

    @Test
    void routesBusinessOperationDocumentQueryToKnowledge() {
        ChatQueryAnalysis analysis = service.analyze(new ChatQueryRequest(
                "帮我查一下我的订单物流状态",
                "kb-1",
                "session-1",
                List.of()
        ));

        assertThat(analysis.intent()).isEqualTo(QueryIntent.KNOWLEDGE);
        assertThat(analysis.route()).isEqualTo("knowledge_retrieval");
        assertThat(analysis.reasons()).anyMatch(reason -> reason.startsWith("contains_business_operation_keyword:"));
    }

    @Test
    void routesOutOfDomainQuestionToFollowUp() {
        ChatQueryAnalysis analysis = service.analyze(new ChatQueryRequest(
                "介绍一下量子计算的发展历史",
                "kb-1",
                "session-1",
                List.of()
        ));

        assertThat(analysis.intent()).isEqualTo(QueryIntent.FOLLOW_UP);
        assertThat(analysis.route()).isEqualTo("ask_follow_up");
        assertThat(analysis.reasons()).contains("outside_business_operation_domain");
    }

    @Test
    void routesAmbiguousIncompleteQuestionToFollowUp() {
        ChatQueryAnalysis analysis = service.analyze(new ChatQueryRequest(
                "怎么办",
                "kb-1",
                "session-1",
                List.of()
        ));

        assertThat(analysis.intent()).isEqualTo(QueryIntent.FOLLOW_UP);
        assertThat(analysis.route()).isEqualTo("ask_follow_up");
        assertThat(analysis.reasons()).contains("short_query_without_history");
    }

    @Test
    void decomposesIndependentBusinessKnowledgeIntents() {
        ChatQueryAnalysis analysis = service.analyze(new ChatQueryRequest(
                "退货流程是什么，运费谁承担？",
                "kb-1",
                "session-1",
                List.of()
        ));

        assertThat(analysis.intent()).isEqualTo(QueryIntent.KNOWLEDGE);
        assertThat(analysis.retrievalQueries()).containsExactly("退货流程是什么", "运费谁承担？");
    }
}
