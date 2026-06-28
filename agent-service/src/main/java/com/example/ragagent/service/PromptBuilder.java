package com.example.ragagent.service;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.RetrievalHit;
import com.example.ragagent.dto.WebSearchResult;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PromptBuilder {
    private static final int MAX_HISTORY_MESSAGES = 8;

    private final RagProperties properties;

    public PromptBuilder(RagProperties properties) {
        this.properties = properties;
    }

    public String systemPrompt() {
        return """
                你是企业知识库问答 Agent。必须遵守：
                1. 优先依据提供的知识库片段回答。
                2. 不能从片段推出的内容，要明确说明没有依据。
                3. 使用简洁中文回答。
                4. 引用知识库内容时使用 [1]、[2] 这样的编号。
                5. 不要编造文档名、政策条款或系统状态。
                """;
    }

    public String webSearchSystemPrompt() {
        return """
                你是带联网搜索工具的问答 Agent。必须遵守：
                1. 优先依据提供的网页搜索结果回答。
                2. 涉及天气、新闻、价格、日期等实时信息时，要说明信息来自搜索结果。
                3. 使用简洁中文回答。
                4. 引用网页结果时使用 [1]、[2] 这样的编号。
                5. 如果搜索结果不足以回答，要明确说明不能确认。
                """;
    }

    public String toolSystemPrompt() {
        return """
                你是带 MCP 工具调用能力的问答 Agent。必须遵守：
                1. 优先依据 MCP 工具返回的结构化结果回答。
                2. 不要编造工具没有返回的字段、状态或业务数据。
                3. 使用简洁中文回答，并保留关键 ID、名称、状态、时间等可核对信息。
                4. 如果工具结果是错误或信息不足，要明确说明不能确认。
                """;
    }

    public String userPrompt(ChatRequest request, QueryAnalysisResponse analysis, List<RetrievalHit> hits) {
        return userPrompt(request, analysis, hits, "");
    }

    public String userPrompt(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            List<RetrievalHit> hits,
            String reflectionHint
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户问题：").append(request.query()).append("\n");
        prompt.append("改写后问题：").append(nullToEmpty(analysis.rewrittenQuery())).append("\n");
        prompt.append("意图：").append(nullToEmpty(analysis.intent())).append("\n\n");

        appendHistory(prompt, request.normalizedHistory());
        appendContext(prompt, hits);

        prompt.append("""

                请基于上面的知识库片段回答用户问题。
                如果答案依赖某个片段，请在对应句子后标注引用编号。
                """);
        appendReflectionHint(prompt, reflectionHint);
        return prompt.toString();
    }

    public String webSearchPrompt(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ToolDecision toolDecision,
            List<WebSearchResult> results
    ) {
        return webSearchPrompt(request, analysis, toolDecision, results, "");
    }

    public String webSearchPrompt(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            ToolDecision toolDecision,
            List<WebSearchResult> results,
            String reflectionHint
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户问题：").append(request.query()).append("\n");
        prompt.append("改写后问题：").append(nullToEmpty(analysis.rewrittenQuery())).append("\n");
        prompt.append("工具：").append(toolDecision.toolName()).append("\n");
        prompt.append("搜索 query：").append(toolDecision.query()).append("\n\n");

        appendHistory(prompt, request.normalizedHistory());
        appendWebResults(prompt, results);

        prompt.append("""

                请基于上面的网页搜索结果回答用户问题。
                如果答案依赖某个网页结果，请在对应句子后标注引用编号。
                """);
        appendReflectionHint(prompt, reflectionHint);
        return prompt.toString();
    }

    public String mcpToolPrompt(ChatRequest request, QueryAnalysisResponse analysis, AgentToolResult toolResult) {
        return mcpToolPrompt(request, analysis, toolResult, "");
    }

    public String mcpToolPrompt(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            AgentToolResult toolResult,
            String reflectionHint
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户问题：").append(request.query()).append("\n");
        prompt.append("改写后问题：").append(nullToEmpty(analysis.rewrittenQuery())).append("\n");
        prompt.append("意图：").append(nullToEmpty(analysis.intent())).append("\n");
        prompt.append("工具：").append(nullToEmpty(toolResult.toolName())).append("\n");
        prompt.append("工具 query：").append(nullToEmpty(toolResult.query())).append("\n\n");

        appendHistory(prompt, request.normalizedHistory());

        prompt.append("MCP 工具返回：\n");
        prompt.append(trim(nullToEmpty(toolResult.observation()), properties.prompt().maxContextCharacters()));
        prompt.append("""

                请基于上面的 MCP 工具返回回答用户问题。
                如果工具返回不足以回答，请直接说明还缺少什么。
                """);
        appendReflectionHint(prompt, reflectionHint);
        return prompt.toString();
    }

    private void appendReflectionHint(StringBuilder prompt, String reflectionHint) {
        if (reflectionHint == null || reflectionHint.isBlank()) {
            return;
        }
        prompt.append("\n反思提示：").append(reflectionHint).append("\n");
    }

    private void appendHistory(StringBuilder prompt, List<ChatMessage> history) {
        if (history.isEmpty()) {
            return;
        }

        prompt.append("最近会话：\n");
        List<ChatMessage> memoryMessages = history.stream()
                .filter(message -> message.role() != null && message.role().startsWith("memory_"))
                .toList();
        List<ChatMessage> chatMessages = history.stream()
                .filter(message -> message.role() == null || !message.role().startsWith("memory_"))
                .toList();
        for (ChatMessage message : memoryMessages) {
            appendHistoryMessage(prompt, message, 1200);
        }
        int start = Math.max(0, chatMessages.size() - MAX_HISTORY_MESSAGES);
        for (ChatMessage message : chatMessages.subList(start, chatMessages.size())) {
            appendHistoryMessage(prompt, message, 500);
        }
        prompt.append("\n");
    }

    private void appendHistoryMessage(StringBuilder prompt, ChatMessage message, int maxLength) {
        prompt.append("- ")
                .append(message.role())
                .append(": ")
                .append(trim(message.content(), maxLength))
                .append("\n");
    }

    private void appendContext(StringBuilder prompt, List<RetrievalHit> hits) {
        prompt.append("知识库片段：\n");
        if (hits.isEmpty()) {
            prompt.append("(无检索结果)\n");
            return;
        }

        int remaining = properties.prompt().maxContextCharacters();
        for (RetrievalHit hit : hits) {
            String block = "[%d] 文档：%s，chunk：%s，分数：%.4f\n%s\n\n".formatted(
                    hit.index(),
                    nullToEmpty(hit.documentName()),
                    nullToEmpty(hit.chunkId()),
                    hit.score(),
                    nullToEmpty(hit.content())
            );
            if (block.length() > remaining) {
                prompt.append(trim(block, remaining));
                break;
            }
            prompt.append(block);
            remaining -= block.length();
            if (remaining <= 0) {
                break;
            }
        }
    }

    private void appendWebResults(StringBuilder prompt, List<WebSearchResult> results) {
        prompt.append("网页搜索结果：\n");
        if (results.isEmpty()) {
            prompt.append("(无搜索结果)\n");
            return;
        }

        int remaining = properties.prompt().maxContextCharacters();
        for (WebSearchResult result : results) {
            String block = "[%d] 标题：%s\nURL：%s\n摘要：%s\n\n".formatted(
                    result.index(),
                    nullToEmpty(result.title()),
                    nullToEmpty(result.url()),
                    nullToEmpty(result.snippet())
            );
            if (block.length() > remaining) {
                prompt.append(trim(block, remaining));
                break;
            }
            prompt.append(block);
            remaining -= block.length();
            if (remaining <= 0) {
                break;
            }
        }
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
