package com.example.ragagent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class IntentTreeClassifier {
    private static final double CLARIFICATION_CONFIDENCE_THRESHOLD = 0.70;

    public IntentTreeDecision classify(
            String normalizedQuery,
            IntentResult intent,
            QueryRewriteResult rewrite
    ) {
        return classify(normalizedQuery, intent, rewrite, null);
    }

    public IntentTreeDecision classify(
            String normalizedQuery,
            IntentResult intent,
            QueryRewriteResult rewrite,
            LlmIntentClassification llmClassification
    ) {
        if (llmClassification != null) {
            List<String> reasons = new ArrayList<>();
            reasons.add("intent_tree_source:llm_json");
            reasons.addAll(llmClassification.reasons());
            return new IntentTreeDecision(
                    llmClassification.requestType(),
                    llmClassification.executionMode(),
                    llmClassification.requiredCapabilities(),
                    llmClassification.clarificationQuestion(),
                    llmClassification.slots(),
                    llmClassification.systemCommand(),
                    reasons
            );
        }

        List<String> reasons = new ArrayList<>();
        QueryIntent queryIntent = intent.intent();

        if (queryIntent == QueryIntent.SYSTEM_COMMAND) {
            String command = detectSystemCommand(normalizedQuery);
            reasons.add("request_type:system_command");
            reasons.add("execution_mode:direct");
            return new IntentTreeDecision(
                    "SYSTEM_COMMAND",
                    "DIRECT",
                    List.of(),
                    "",
                    command.isBlank() ? Map.of() : Map.of("command", command),
                    command,
                    reasons
            );
        }

        if (queryIntent == QueryIntent.CHITCHAT) {
            reasons.add("request_type:chitchat");
            reasons.add("execution_mode:direct");
            return new IntentTreeDecision("CHITCHAT", "DIRECT", List.of(), "", Map.of(), "", reasons);
        }

        if (queryIntent == QueryIntent.TOOL) {
            String capability = requiresWebSearch(normalizedQuery) ? "web_search"
                    : requiresFunctionCall(normalizedQuery) ? "function_call" : "mcp_tool";
            reasons.add("request_type:tool_request");
            reasons.add("execution_mode:single_tool");
            reasons.add("required_capability:" + capability);
            return new IntentTreeDecision("TOOL_REQUEST", "SINGLE_TOOL", List.of(capability), "", Map.of(), "", reasons);
        }

        if (queryIntent == QueryIntent.KNOWLEDGE) {
            String executionMode = rewrite.retrievalQueries().size() > 1 ? "ITERATIVE_TOOL" : "SINGLE_TOOL";
            reasons.add("request_type:user_question");
            reasons.add("execution_mode:" + executionMode.toLowerCase());
            reasons.add("required_capability:rag_retrieval");
            return new IntentTreeDecision("USER_QUESTION", executionMode, List.of("rag_retrieval"), "", Map.of(), "", reasons);
        }

        String clarification = clarificationQuestion(queryIntent, intent.confidence());
        reasons.add("request_type:user_question");
        reasons.add("execution_mode:direct");
        if (intent.confidence() < CLARIFICATION_CONFIDENCE_THRESHOLD) {
            reasons.add("low_confidence_clarification");
        }
        return new IntentTreeDecision("USER_QUESTION", "DIRECT", List.of(), clarification, Map.of(), "", reasons);
    }

    private boolean requiresWebSearch(String query) {
        String lower = query.toLowerCase();
        return query.contains("今天")
                || query.contains("现在")
                || query.contains("实时")
                || query.contains("最新")
                || query.contains("新闻")
                || query.contains("天气")
                || query.contains("气温")
                || query.contains("预报")
                || query.contains("股价")
                || query.contains("汇率")
                || lower.contains("today")
                || lower.contains("current")
                || lower.contains("latest")
                || lower.contains("news")
                || lower.contains("weather");
    }

    private boolean requiresFunctionCall(String query) {
        String lower = query.toLowerCase();
        return lower.contains("function") || lower.contains("local function") || query.contains("本地函数") || query.contains("调用函数");
    }

    private String detectSystemCommand(String query) {
        String lower = query.toLowerCase();
        if (lower.startsWith("/clear-memory") || query.contains("清空记忆")) {
            return "CLEAR_MEMORY";
        }
        if (lower.startsWith("/switch-kb") || query.contains("切换知识库")) {
            return "SWITCH_KNOWLEDGE_BASE";
        }
        if (query.contains("检索调试") || query.contains("调试信息")) {
            return "ENABLE_RETRIEVAL_DEBUG";
        }
        if (lower.contains("topk") || query.contains("召回数量")) {
            return "CHANGE_TOP_K";
        }
        return "SYSTEM_COMMAND";
    }

    private String clarificationQuestion(QueryIntent intent, double confidence) {
        if (intent == QueryIntent.CLARIFICATION || confidence < CLARIFICATION_CONFIDENCE_THRESHOLD) {
            return "这个问题还需要补充信息。请说明你要处理的业务场景、对象或希望我调用的能力。";
        }
        return "请补充你要处理的是订单、物流、余额、退货、提交、修改地址、取消订单还是售后维修中的哪一类。";
    }
}
