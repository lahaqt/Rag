package com.example.ragagent.service;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.RetrievalHit;
import com.example.ragagent.dto.WebSearchResult;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AnswerGenerator {
    private final LlmGateway llmGateway;
    private final PromptBuilder promptBuilder;
    private final RagProperties properties;

    public AnswerGenerator(LlmGateway llmGateway, PromptBuilder promptBuilder, RagProperties properties) {
        this.llmGateway = llmGateway;
        this.promptBuilder = promptBuilder;
        this.properties = properties;
    }

    public AnswerDraft generate(ChatRequest request, QueryAnalysisResponse analysis, List<RetrievalHit> hits) {
        return generate(request, analysis, hits, "");
    }

    public AnswerDraft generate(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            List<RetrievalHit> hits,
            String reflectionHint
    ) {
        if ("SYSTEM_COMMAND".equalsIgnoreCase(analysis.requestType()) || "system_command".equals(analysis.route())) {
            String command = isBlank(analysis.systemCommand()) ? "SYSTEM_COMMAND" : analysis.systemCommand();
            return new AnswerDraft("已识别系统指令：" + command + "。当前聊天主链路只负责识别和路由，实际状态变更需要由对应的系统指令处理器接入。", false, "system_command_recognized");
        }
        if ("follow_up".equals(analysis.intent()) || "ask_follow_up".equals(analysis.route())) {
            if (!isBlank(analysis.clarificationQuestion())) {
                return new AnswerDraft(analysis.clarificationQuestion(), false, "follow_up_required");
            }
            return new AnswerDraft("这个问题还需要补充信息。请说明你要处理的是订单、物流、余额、退货、提交、修改地址、取消订单还是售后维修中的哪一类，并补充必要的订单号、商品或操作场景。", false, "follow_up_required");
        }
        if ("clarification".equals(analysis.intent())) {
            if (!isBlank(analysis.clarificationQuestion())) {
                return new AnswerDraft(analysis.clarificationQuestion(), false, "clarification_required");
            }
            return new AnswerDraft("这个问题还不够明确。请补充你要查询的产品、制度、文档或业务场景。", false, "clarification_required");
        }
        if ("tool".equals(analysis.intent())) {
            return new AnswerDraft("这个问题需要调用业务工具或个人数据接口。当前已完成路由识别，但工具执行层尚未接入。", false, "tool_not_connected");
        }
        if ("chitchat".equals(analysis.intent()) && hits.isEmpty()) {
            return new AnswerDraft("你好，我可以基于当前知识库帮你检索资料并生成带引用的答案。", false, "direct_reply");
        }
        if (isBlank(request.knowledgeBaseId())) {
            return new AnswerDraft("请先选择一个知识库，再发起知识问答。", false, "knowledge_base_required");
        }
        if (hits.isEmpty()) {
            return new AnswerDraft("没有在当前知识库中检索到足够相关的片段。可以尝试换一种问法，或先上传相关文档。", false, "no_retrieval_hits");
        }

        if (llmGateway.isConfigured()) {
            try {
                String answer = llmGateway.complete(
                        promptBuilder.systemPrompt(),
                        promptBuilder.userPrompt(request, analysis, hits, reflectionHint),
                        properties.llm().temperature(),
                        properties.llm().maxTokens()
                );
                String finishReason = reflectionHint == null || reflectionHint.isBlank()
                        ? "llm_generated" : "llm_generated_reflection_retry";
                return new AnswerDraft(answer, true, finishReason);
            } catch (Exception exception) {
                String finishReason = reflectionHint == null || reflectionHint.isBlank()
                        ? "llm_failed_retrieval_fallback" : "llm_failed_retrieval_fallback_retry";
                return new AnswerDraft(fallbackAnswer(hits, "大模型调用失败：" + exception.getMessage()), false, finishReason);
            }
        }

        String finishReason = reflectionHint == null || reflectionHint.isBlank()
                ? "llm_not_configured_retrieval_fallback" : "llm_not_configured_retrieval_fallback_retry";
        return new AnswerDraft(fallbackAnswer(hits, "当前未配置可用的大模型 API Key。"), false, finishReason);
    }

    public AnswerDraft generateFromWebSearch(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ToolDecision toolDecision,
            List<WebSearchResult> results
    ) {
        return generateFromWebSearch(request, analysis, toolDecision, results, "");
    }

    public AnswerDraft generateFromWebSearch(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ToolDecision toolDecision,
            List<WebSearchResult> results,
            String reflectionHint
    ) {
        if (results.isEmpty()) {
            return new AnswerDraft("联网搜索没有返回可用结果。可以换一个更具体的问题再试。", false, "web_search_no_results");
        }

        if (llmGateway.isConfigured()) {
            try {
                String answer = llmGateway.complete(
                        promptBuilder.webSearchSystemPrompt(),
                        promptBuilder.webSearchPrompt(request, analysis, toolDecision, results, reflectionHint),
                        properties.llm().temperature(),
                        properties.llm().maxTokens()
                );
                boolean retry = reflectionHint != null && !reflectionHint.isBlank();
                return new AnswerDraft(answer, true,
                        retry ? "web_search_llm_generated_retry" : "web_search_llm_generated");
            } catch (Exception exception) {
                return new AnswerDraft(webSearchFallback(results, "大模型调用失败：" + exception.getMessage()),
                        false,
                        reflectionHint == null || reflectionHint.isBlank()
                                ? "web_search_llm_failed_fallback"
                                : "web_search_llm_failed_fallback_retry");
            }
        }

        return new AnswerDraft(webSearchFallback(results, "当前未配置可用的大模型 API Key。"),
                false,
                reflectionHint == null || reflectionHint.isBlank()
                        ? "web_search_llm_not_configured_fallback"
                        : "web_search_llm_not_configured_fallback_retry");
    }

    public AnswerDraft generateFromMcpTool(ChatRequest request, QueryAnalysisResponse analysis, AgentToolResult toolResult) {
        return generateFromMcpTool(request, analysis, toolResult, "");
    }

    public AnswerDraft generateFromMcpTool(ChatRequest request, QueryAnalysisResponse analysis, AgentToolResult toolResult, String reflectionHint) {
        if (llmGateway.isConfigured()) {
            try {
                String answer = llmGateway.complete(
                        promptBuilder.toolSystemPrompt(),
                        promptBuilder.mcpToolPrompt(request, analysis, toolResult, reflectionHint),
                        properties.llm().temperature(),
                        properties.llm().maxTokens()
                );
                boolean retry = reflectionHint != null && !reflectionHint.isBlank();
                return new AnswerDraft(answer, true,
                        retry ? "mcp_tool_llm_generated_retry" : "mcp_tool_llm_generated");
            } catch (Exception exception) {
                return new AnswerDraft(mcpFallback(toolResult, "大模型调用失败：" + exception.getMessage()),
                        false,
                        reflectionHint == null || reflectionHint.isBlank()
                                ? "mcp_tool_llm_failed_fallback"
                                : "mcp_tool_llm_failed_fallback_retry");
            }
        }

        return new AnswerDraft(mcpFallback(toolResult, "当前未配置可用的大模型 API Key。"),
                false,
                reflectionHint == null || reflectionHint.isBlank()
                        ? "mcp_tool_llm_not_configured_fallback"
                        : "mcp_tool_llm_not_configured_fallback_retry");
    }

    private String fallbackAnswer(List<RetrievalHit> hits, String reason) {
        StringBuilder answer = new StringBuilder();
        answer.append(reason).append("先返回检索摘要：\n\n");
        for (RetrievalHit hit : hits) {
            answer.append("[")
                    .append(hit.index())
                    .append("] ")
                    .append(excerpt(hit.content(), 180))
                    .append("\n");
        }
        return answer.toString().trim();
    }

    private String webSearchFallback(List<WebSearchResult> results, String reason) {
        StringBuilder answer = new StringBuilder();
        answer.append(reason).append("先返回联网搜索摘要：\n\n");
        for (WebSearchResult result : results) {
            answer.append("[")
                    .append(result.index())
                    .append("] ")
                    .append(result.title())
                    .append("\n")
                    .append(excerpt(result.snippet(), 180))
                    .append("\n")
                    .append(result.url())
                    .append("\n");
        }
        return answer.toString().trim();
    }

    private String mcpFallback(AgentToolResult toolResult, String reason) {
        return reason + "先返回 MCP 工具结果：\n\n" + safeObservation(toolResult);
    }

    private String safeObservation(AgentToolResult toolResult) {
        return toolResult == null || toolResult.observation() == null ? "" : toolResult.observation();
    }

    private String excerpt(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
