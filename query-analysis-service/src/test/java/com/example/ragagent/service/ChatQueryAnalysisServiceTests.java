package com.example.ragagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatQueryRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        assertThat(analysis.requestType()).isEqualTo("CHITCHAT");
        assertThat(analysis.executionMode()).isEqualTo("DIRECT");
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
        assertThat(analysis.requestType()).isEqualTo("USER_QUESTION");
        assertThat(analysis.executionMode()).isEqualTo("SINGLE_TOOL");
        assertThat(analysis.requiredCapabilities()).containsExactly("rag_retrieval");
        assertThat(analysis.reasons()).anyMatch(reason -> reason.startsWith("contains_business_operation_keyword:"));
    }

    @Test
    void routesRealtimeQuestionToWebSearchCapability() {
        ChatQueryAnalysis analysis = service.analyze(new ChatQueryRequest(
                "今天北京天气怎么样？",
                "kb-1",
                "session-1",
                List.of()
        ));

        assertThat(analysis.intent()).isEqualTo(QueryIntent.TOOL);
        assertThat(analysis.route()).isEqualTo("tool_invocation");
        assertThat(analysis.requestType()).isEqualTo("TOOL_REQUEST");
        assertThat(analysis.executionMode()).isEqualTo("SINGLE_TOOL");
        assertThat(analysis.requiredCapabilities()).containsExactly("web_search");
    }

    @Test
    void detectsSystemCommandIntent() {
        ChatQueryAnalysis analysis = service.analyze(new ChatQueryRequest(
                "清空记忆",
                "kb-1",
                "session-1",
                List.of()
        ));

        assertThat(analysis.intent()).isEqualTo(QueryIntent.SYSTEM_COMMAND);
        assertThat(analysis.route()).isEqualTo("system_command");
        assertThat(analysis.requestType()).isEqualTo("SYSTEM_COMMAND");
        assertThat(analysis.executionMode()).isEqualTo("DIRECT");
        assertThat(analysis.systemCommand()).isEqualTo("CLEAR_MEMORY");
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
        assertThat(analysis.executionMode()).isEqualTo("DIRECT");
        assertThat(analysis.clarificationQuestion()).isNotBlank();
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
        assertThat(analysis.executionMode()).isEqualTo("ITERATIVE_TOOL");
    }

    @Test
    void llmJsonIntentClassifierOverridesRuleFallback() {
        ChatQueryAnalysisService llmBackedService = new ChatQueryAnalysisService(
                new IntentClassifier(),
                new QueryRewriteService(),
                new IntentTreeClassifier(),
                new LlmIntentClassifier(new FixedLlmChatClient("""
                        {
                          "intent": "tool",
                          "confidence": 0.93,
                          "requestType": "TOOL_REQUEST",
                          "executionMode": "SINGLE_TOOL",
                          "requiredCapabilities": ["mcp_tool"],
                          "clarificationQuestion": "",
                          "slots": {"server": "crm"},
                          "systemCommand": "",
                          "reasons": ["explicit_mcp_tool_request"]
                        }
                        """), new ObjectMapper())
        );

        ChatQueryAnalysis analysis = llmBackedService.analyze(new ChatQueryRequest(
                "帮我调用 CRM MCP 工具查客户状态",
                "kb-1",
                "session-1",
                List.of()
        ));

        assertThat(analysis.intent()).isEqualTo(QueryIntent.TOOL);
        assertThat(analysis.confidence()).isEqualTo(0.93);
        assertThat(analysis.requestType()).isEqualTo("TOOL_REQUEST");
        assertThat(analysis.executionMode()).isEqualTo("SINGLE_TOOL");
        assertThat(analysis.requiredCapabilities()).containsExactly("mcp_tool");
        assertThat(analysis.slots()).containsEntry("server", "crm");
        assertThat(analysis.reasons()).contains("llm_json_intent", "intent_tree_source:llm_json");
    }

    private static class FixedLlmChatClient extends LlmChatClient {
        private final String response;

        FixedLlmChatClient(String response) {
            super(new RagProperties(null, null));
            this.response = response;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String complete(String prompt, double temperature, int maxTokens) {
            return response;
        }
    }
}
