package com.example.ragagent.service;

import com.example.ragagent.dto.ChatMessage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LlmIntentClassifier {
    private static final Logger log = LoggerFactory.getLogger(LlmIntentClassifier.class);
    private static final Set<String> REQUEST_TYPES = Set.of(
            "USER_QUESTION", "TOOL_REQUEST", "SYSTEM_COMMAND", "CHITCHAT", "UNSAFE_OR_UNSUPPORTED"
    );
    private static final Set<String> EXECUTION_MODES = Set.of(
            "DIRECT", "SINGLE_TOOL", "ITERATIVE_TOOL", "PLANNED_TASK"
    );
    private static final Set<String> CAPABILITIES = Set.of("rag_retrieval", "web_search", "mcp_tool", "function_call");
    private static final String PROMPT_TEMPLATE = """
            你是 RAG Agent 的意图识别 JSON 分类器。只输出一个 JSON 对象，不要输出 Markdown、解释或代码块。

            项目业务边界：
            - 知识库只覆盖业务操作文档：订单、物流、余额、退款、退货、提交、修改地址、取消订单、售后维修、保修等。
            - 实时、最新、天气、新闻、股价、汇率等问题应走工具能力 web_search。
            - 明确要求调用 MCP、工具、外部系统或个人数据接口的问题应走工具能力 mcp_tool。
            - 清空记忆、切换知识库、修改检索参数、开启调试等是系统指令。
            - 信息不足、低置信度或超出业务边界的问题应触发澄清反问。

            允许值：
            intent: knowledge | tool | system_command | chitchat | follow_up | clarification
            requestType: USER_QUESTION | TOOL_REQUEST | SYSTEM_COMMAND | CHITCHAT | UNSAFE_OR_UNSUPPORTED
            executionMode: DIRECT | SINGLE_TOOL | ITERATIVE_TOOL | PLANNED_TASK
            requiredCapabilities: rag_retrieval | web_search | mcp_tool | function_call
            systemCommand: CLEAR_MEMORY | SWITCH_KNOWLEDGE_BASE | CHANGE_TOP_K | ENABLE_RETRIEVAL_DEBUG | EXPORT_CHAT | MANAGE_MCP_SERVER | SYSTEM_COMMAND | ""

            输出 JSON schema：
            {
              "intent": "knowledge",
              "confidence": 0.0,
              "requestType": "USER_QUESTION",
              "executionMode": "SINGLE_TOOL",
              "requiredCapabilities": ["rag_retrieval"],
              "clarificationQuestion": "",
              "slots": {},
              "systemCommand": "",
              "reasons": ["short_reason"]
            }

            判定要求：
            - confidence 必须是 0 到 1。
            - 知识库问答使用 intent=knowledge, requestType=USER_QUESTION, requiredCapabilities=["rag_retrieval"]。
            - 联网/实时工具使用 intent=tool, requestType=TOOL_REQUEST, requiredCapabilities=["web_search"]。
            - MCP 工具使用 intent=tool, requestType=TOOL_REQUEST, requiredCapabilities=["mcp_tool"]。
            - 本地业务 Function 使用 intent=tool, requestType=TOOL_REQUEST, requiredCapabilities=["function_call"]。
            - 系统指令使用 intent=system_command, requestType=SYSTEM_COMMAND, executionMode=DIRECT。
            - 闲聊使用 intent=chitchat, requestType=CHITCHAT, executionMode=DIRECT。
            - 需要澄清时使用 intent=follow_up 或 clarification, executionMode=DIRECT，并给出 clarificationQuestion。
            - 多个独立知识库子问题使用 executionMode=ITERATIVE_TOOL。

            对话历史：
            %s

            用户最新问题：
            %s
            """;

    private final LlmChatClient llmChatClient;
    private final ObjectMapper objectMapper;

    public LlmIntentClassifier(LlmChatClient llmChatClient, ObjectMapper objectMapper) {
        this.llmChatClient = llmChatClient;
        this.objectMapper = objectMapper;
    }

    public Optional<LlmIntentClassification> classify(List<ChatMessage> history, String normalizedQuery) {
        if (llmChatClient == null || !llmChatClient.isConfigured()) {
            return Optional.empty();
        }
        try {
            String prompt = PROMPT_TEMPLATE.formatted(formatHistory(history), normalizedQuery);
            String output = llmChatClient.complete(prompt, 0.0, 700);
            return parse(output);
        } catch (Exception exception) {
            log.warn("LLM intent classification failed, falling back to rule classifier: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    Optional<LlmIntentClassification> parse(String output) {
        String json = extractJson(output);
        if (json.isBlank()) {
            return Optional.empty();
        }
        try {
            LlmIntentJson parsed = objectMapper.readValue(json, LlmIntentJson.class);
            QueryIntent intent = parseIntent(parsed.intent());
            if (intent == null) {
                return Optional.empty();
            }
            String requestType = validOrDefault(parsed.requestType(), REQUEST_TYPES, inferRequestType(intent));
            String executionMode = validOrDefault(parsed.executionMode(), EXECUTION_MODES, inferExecutionMode(intent));
            List<String> capabilities = sanitizeCapabilities(parsed.requiredCapabilities());
            return Optional.of(new LlmIntentClassification(
                    intent,
                    parsed.confidence(),
                    requestType,
                    executionMode,
                    capabilities,
                    parsed.clarificationQuestion(),
                    parsed.slots(),
                    parsed.systemCommand(),
                    parsed.reasons()
            ));
        } catch (JsonProcessingException exception) {
            log.warn("LLM intent classification returned invalid JSON, falling back. output={}", output);
            return Optional.empty();
        }
    }

    private QueryIntent parseIntent(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase()) {
            case "knowledge" -> QueryIntent.KNOWLEDGE;
            case "tool" -> QueryIntent.TOOL;
            case "system_command" -> QueryIntent.SYSTEM_COMMAND;
            case "chitchat" -> QueryIntent.CHITCHAT;
            case "follow_up" -> QueryIntent.FOLLOW_UP;
            case "clarification" -> QueryIntent.CLARIFICATION;
            default -> null;
        };
    }

    private List<String> sanitizeCapabilities(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> sanitized = new ArrayList<>();
        for (String value : values) {
            if (CAPABILITIES.contains(value) && !sanitized.contains(value)) {
                sanitized.add(value);
            }
        }
        return List.copyOf(sanitized);
    }

    private String extractJson(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String trimmed = output.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return trimmed.substring(start, end + 1);
    }

    private String formatHistory(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "（无历史对话）";
        }
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : history) {
            builder.append(message.role()).append(": ").append(message.content()).append('\n');
        }
        return builder.toString().trim();
    }

    private String validOrDefault(String value, Set<String> allowed, String fallback) {
        return value != null && allowed.contains(value) ? value : fallback;
    }

    private String inferRequestType(QueryIntent intent) {
        return switch (intent) {
            case TOOL -> "TOOL_REQUEST";
            case SYSTEM_COMMAND -> "SYSTEM_COMMAND";
            case CHITCHAT -> "CHITCHAT";
            default -> "USER_QUESTION";
        };
    }

    private String inferExecutionMode(QueryIntent intent) {
        return switch (intent) {
            case KNOWLEDGE, TOOL -> "SINGLE_TOOL";
            default -> "DIRECT";
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LlmIntentJson(
            String intent,
            double confidence,
            String requestType,
            String executionMode,
            List<String> requiredCapabilities,
            String clarificationQuestion,
            Map<String, String> slots,
            String systemCommand,
            List<String> reasons
    ) {
    }
}
