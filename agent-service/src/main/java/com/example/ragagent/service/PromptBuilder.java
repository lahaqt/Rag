package com.example.ragagent.service;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatAttachment;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.RetrievalHit;
import com.example.ragagent.dto.WebSearchResult;
import com.example.ragagent.memory.TokenEstimator;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PromptBuilder {
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

    public String multiAgentSystemPrompt() {
        return """
                你是企业 multi-agent 综合回答 Agent。必须遵守：
                1. 同时综合知识库检索、联网搜索和工具调用结果。
                2. 知识库证据优先用于业务规则、流程和制度类结论。
                3. 联网搜索只用于实时信息、外部状态或补充背景。
                4. MCP 工具结果只按工具返回内容陈述，不要补造字段。
                5. 如果不同来源冲突，要明确区分来源并说明不确定性。
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

        int remainingContextTokens = initialContextBudget(systemPrompt(), prompt);
        remainingContextTokens = appendAttachments(
                prompt,
                request.normalizedAttachments(),
                remainingContextTokens
        );
        remainingContextTokens = appendHistory(prompt, request.normalizedHistory(), remainingContextTokens);
        appendContext(prompt, hits, remainingContextTokens);

        prompt.append("""

                请基于上面的知识库片段回答用户问题。
                每个可核验的事实结论都必须在对应句子后紧邻标注一个或多个片段编号，例如“退款会原路退回。[2]”。
                只能使用已提供的片段编号；不要在答案末尾集中罗列编号，也不要编造来源、编号或原文。
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

        int remainingContextTokens = initialContextBudget(webSearchSystemPrompt(), prompt);
        remainingContextTokens = appendAttachments(
                prompt,
                request.normalizedAttachments(),
                remainingContextTokens
        );
        remainingContextTokens = appendHistory(prompt, request.normalizedHistory(), remainingContextTokens);
        appendWebResults(prompt, results, remainingContextTokens);

        prompt.append("""

                请基于上面的网页搜索结果回答用户问题。
                每个可核验的事实结论都必须在对应句子后紧邻标注一个或多个结果编号。
                只能使用已提供的结果编号；不要在答案末尾集中罗列编号，也不要编造来源、编号或原文。
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

        int remainingContextTokens = initialContextBudget(toolSystemPrompt(), prompt);
        remainingContextTokens = appendAttachments(
                prompt,
                request.normalizedAttachments(),
                remainingContextTokens
        );
        remainingContextTokens = appendHistory(prompt, request.normalizedHistory(), remainingContextTokens);

        prompt.append("MCP 工具返回：\n");
        prompt.append(TokenEstimator.truncate(nullToEmpty(toolResult.observation()), remainingContextTokens));
        prompt.append("""

                请基于上面的 MCP 工具返回回答用户问题。
                如果工具返回不足以回答，请直接说明还缺少什么。
                """);
        appendReflectionHint(prompt, reflectionHint);
        return prompt.toString();
    }

    public String multiAgentPrompt(
            ChatRequest request,
            QueryAnalysisResponse analysis,
            AgentToolResult toolResult,
            String reflectionHint
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户问题：").append(request.query()).append("\n");
        prompt.append("改写后问题：").append(nullToEmpty(analysis.rewrittenQuery())).append("\n");
        prompt.append("意图：").append(nullToEmpty(analysis.intent())).append("\n");
        prompt.append("执行结果：").append(nullToEmpty(toolResult.finishReason())).append("\n\n");

        int remainingContextTokens = initialContextBudget(multiAgentSystemPrompt(), prompt);
        remainingContextTokens = appendAttachments(
                prompt,
                request.normalizedAttachments(),
                remainingContextTokens
        );
        remainingContextTokens = appendHistory(prompt, request.normalizedHistory(), remainingContextTokens);
        remainingContextTokens = appendContext(prompt, toolResult.retrievalHits(), remainingContextTokens);
        prompt.append("\n");
        remainingContextTokens = appendWebResults(prompt, toolResult.webSearchResults(), remainingContextTokens);
        prompt.append("\nAgent observations:\n");
        prompt.append(TokenEstimator.truncate(nullToEmpty(toolResult.observation()), remainingContextTokens));
        prompt.append("\nOnly use observations from successful agents as factual evidence; failed-agent messages are diagnostic only.\n");
        prompt.append("""

                请基于上面的多 Agent 结果回答用户问题。
                每个有知识库或网页证据支撑的结论都必须在对应句子后紧邻使用对应编号；不要在答案末尾集中罗列编号。
                只能使用已提供的编号；无法从证据确认的内容要明确说明。
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

    private int initialContextBudget(String systemPrompt, StringBuilder prompt) {
        return Math.max(0, properties.prompt().inputTokenBudget()
                - TokenEstimator.estimate(systemPrompt)
                - TokenEstimator.estimate(prompt.toString()));
    }

    private int appendAttachments(StringBuilder prompt, List<ChatAttachment> attachments, int remainingTokens) {
        if (attachments == null || attachments.isEmpty()) {
            return remainingTokens;
        }

        int componentRemaining = Math.min(remainingTokens, properties.prompt().maxAttachmentTokens());
        int used = 0;
        int index = 1;
        StringBuilder section = new StringBuilder("Attachments:\n");
        for (ChatAttachment attachment : attachments) {
            if (attachment == null || attachment.content() == null || attachment.content().isBlank()) {
                continue;
            }
            String block = "[%d] File: %s\n%s\n\n".formatted(
                    index++,
                    nullToEmpty(attachment.fileName()),
                    nullToEmpty(attachment.content())
            );
            int blockTokens = TokenEstimator.estimate(block);
            if (blockTokens > componentRemaining) {
                section.append(TokenEstimator.truncate(block, componentRemaining));
                used += componentRemaining;
                componentRemaining = 0;
                break;
            }
            section.append(block);
            componentRemaining -= blockTokens;
            used += blockTokens;
            if (componentRemaining <= 0) {
                break;
            }
        }
        if (index == 1) {
            return remainingTokens;
        }
        prompt.append(section).append("\n");
        return Math.max(0, remainingTokens - used);
    }

    private int appendHistory(StringBuilder prompt, List<ChatMessage> history, int remainingTokens) {
        if (history.isEmpty()) {
            return remainingTokens;
        }

        prompt.append("最近会话：\n");
        List<ChatMessage> memoryMessages = history.stream()
                .filter(message -> message.role() != null && message.role().startsWith("memory_"))
                .toList();
        List<ChatMessage> chatMessages = history.stream()
                .filter(message -> message.role() == null || !message.role().startsWith("memory_"))
                .toList();
        int componentBudget = Math.min(remainingTokens, properties.prompt().maxHistoryTokens());
        int memoryBudget = Math.min(componentBudget / 3, TokenEstimator.estimateMessages(memoryMessages));
        int memoryUsed = appendMessages(prompt, memoryMessages, memoryBudget, false);
        int chatUsed = appendMessages(prompt, chatMessages, componentBudget - memoryUsed, true);
        prompt.append("\n");
        return Math.max(0, remainingTokens - memoryUsed - chatUsed);
    }

    private int appendMessages(
            StringBuilder prompt,
            List<ChatMessage> messages,
            int tokenBudget,
            boolean keepNewest
    ) {
        if (messages.isEmpty() || tokenBudget <= 0) {
            return 0;
        }
        List<ChatMessage> selected = new ArrayList<>();
        int selectedTokens = 0;
        if (keepNewest) {
            for (int index = messages.size() - 1; index >= 0; index--) {
                ChatMessage message = messages.get(index);
                int tokens = TokenEstimator.estimate(message);
                if (!selected.isEmpty() && selectedTokens + tokens > tokenBudget) {
                    break;
                }
                selected.add(0, message);
                selectedTokens += tokens;
                if (selectedTokens >= tokenBudget) {
                    break;
                }
            }
        } else {
            for (ChatMessage message : messages) {
                int tokens = TokenEstimator.estimate(message);
                if (!selected.isEmpty() && selectedTokens + tokens > tokenBudget) {
                    break;
                }
                selected.add(message);
                selectedTokens += tokens;
                if (selectedTokens >= tokenBudget) {
                    break;
                }
            }
        }
        int remaining = tokenBudget;
        int used = 0;
        for (ChatMessage message : selected) {
            String prefix = "- " + message.role() + ": ";
            int prefixTokens = TokenEstimator.estimate(prefix);
            if (remaining <= prefixTokens) {
                break;
            }
            String content = TokenEstimator.truncate(message.content(), remaining - prefixTokens);
            String line = prefix + content + "\n";
            int lineTokens = TokenEstimator.estimate(line);
            prompt.append(line);
            remaining -= lineTokens;
            used += lineTokens;
        }
        return used;
    }

    private int appendContext(StringBuilder prompt, List<RetrievalHit> hits) {
        return appendContext(prompt, hits, properties.prompt().inputTokenBudget());
    }

    private int appendContext(StringBuilder prompt, List<RetrievalHit> hits, int remainingTokens) {
        prompt.append("知识库片段：\n");
        if (hits.isEmpty()) {
            prompt.append("(无检索结果)\n");
            return remainingTokens;
        }

        int remaining = remainingTokens;
        for (RetrievalHit hit : hits) {
            String block = "[%d] 文档：%s，chunk：%s，分数：%.4f\n%s\n\n".formatted(
                    hit.index(),
                    nullToEmpty(hit.documentName()),
                    nullToEmpty(hit.chunkId()),
                    hit.score(),
                    nullToEmpty(hit.content())
            );
            int blockTokens = TokenEstimator.estimate(block);
            if (blockTokens > remaining) {
                prompt.append(TokenEstimator.truncate(block, remaining));
                remaining = 0;
                break;
            }
            prompt.append(block);
            remaining -= blockTokens;
            if (remaining <= 0) {
                break;
            }
        }
        return remaining;
    }

    private int appendWebResults(StringBuilder prompt, List<WebSearchResult> results) {
        return appendWebResults(prompt, results, properties.prompt().inputTokenBudget());
    }

    private int appendWebResults(StringBuilder prompt, List<WebSearchResult> results, int remainingTokens) {
        prompt.append("网页搜索结果：\n");
        if (results.isEmpty()) {
            prompt.append("(无搜索结果)\n");
            return remainingTokens;
        }

        int remaining = remainingTokens;
        for (WebSearchResult result : results) {
            String block = "[%d] 标题：%s\nURL：%s\n摘要：%s\n\n".formatted(
                    result.index(),
                    nullToEmpty(result.title()),
                    nullToEmpty(result.url()),
                    nullToEmpty(result.snippet())
            );
            int blockTokens = TokenEstimator.estimate(block);
            if (blockTokens > remaining) {
                prompt.append(TokenEstimator.truncate(block, remaining));
                remaining = 0;
                break;
            }
            prompt.append(block);
            remaining -= blockTokens;
            if (remaining <= 0) {
                break;
            }
        }
        return remaining;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
