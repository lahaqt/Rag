package com.example.ragagent.client;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.observability.TracePropagationInterceptor;
import com.example.ragagent.service.LlmGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LlmChatClient implements LlmGateway {
    private final RestClient openAiRestClient;
    private final RestClient anthropicRestClient;
    private final RagProperties.Llm llm;
    private final ObjectMapper objectMapper;

    public LlmChatClient(
            RagProperties properties,
            RestClient.Builder restClientBuilder,
            TracePropagationInterceptor tracePropagationInterceptor,
            ObjectMapper objectMapper
    ) {
        this.llm = properties.llm();
        this.objectMapper = objectMapper;
        this.openAiRestClient = restClientBuilder.clone()
                .baseUrl(llm.openaiCompatible().baseUrl())
                .requestInterceptor(tracePropagationInterceptor)
                .build();
        this.anthropicRestClient = restClientBuilder.clone()
                .baseUrl(llm.anthropicCompatible().baseUrl())
                .requestInterceptor(tracePropagationInterceptor)
                .build();
    }

    @Override
    public boolean isConfigured() {
        return llm != null
                && llm.apiKey() != null
                && !llm.apiKey().isBlank()
                && ((llm.openaiCompatible() != null && !llm.openaiCompatible().baseUrl().isBlank())
                || (llm.anthropicCompatible() != null && !llm.anthropicCompatible().baseUrl().isBlank()));
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        if (!isConfigured()) {
            throw new IllegalStateException("rag.llm.api-key is required for answer generation.");
        }

        try {
            return completeOpenAiCompatible(systemPrompt, userPrompt, temperature, maxTokens);
        } catch (Exception openAiException) {
            if (llm.anthropicCompatible() == null || llm.anthropicCompatible().baseUrl().isBlank()) {
                throw openAiException;
            }
            return completeAnthropicCompatible(systemPrompt, userPrompt, maxTokens);
        }
    }

    @Override
    public String stream(
            String systemPrompt,
            String userPrompt,
            double temperature,
            int maxTokens,
            Consumer<String> onDelta
    ) {
        if (!isConfigured()) {
            throw new IllegalStateException("rag.llm.api-key is required for answer generation.");
        }

        try {
            return streamOpenAiCompatible(systemPrompt, userPrompt, temperature, maxTokens, onDelta);
        } catch (Exception openAiException) {
            if (llm.anthropicCompatible() == null || llm.anthropicCompatible().baseUrl().isBlank()) {
                throw openAiException;
            }
            return streamAnthropicCompatible(systemPrompt, userPrompt, maxTokens, onDelta);
        }
    }

    private String completeOpenAiCompatible(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        ResponsesApiResponse response = openAiRestClient.post()
                .uri("/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + llm.apiKey())
                .body(new ResponsesApiRequest(
                        llm.model(),
                        List.of(
                                new ResponsesInputMessage("system", systemPrompt),
                                new ResponsesInputMessage("user", userPrompt)
                        ),
                        temperature,
                        maxTokens,
                        false
                ))
                .retrieve()
                .body(ResponsesApiResponse.class);

        String content = extractResponsesApiText(response);
        if (content.isBlank()) {
            throw new IllegalStateException("LLM API returned an empty response.");
        }
        return content.trim();
    }

    private String completeAnthropicCompatible(String systemPrompt, String userPrompt, int maxTokens) {
        AnthropicMessageResponse response = anthropicRestClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", llm.apiKey())
                .header("anthropic-version", "2023-06-01")
                .body(new AnthropicMessageRequest(
                        llm.model(),
                        systemPrompt,
                        maxTokens,
                        List.of(new AnthropicMessage("user", userPrompt)),
                        false
                ))
                .retrieve()
                .body(AnthropicMessageResponse.class);

        if (response == null || response.content() == null || response.content().isEmpty()) {
            throw new IllegalStateException("Anthropic-compatible LLM API returned an empty response.");
        }

        String content = response.content().stream()
                .filter(block -> "text".equals(block.type()))
                .map(AnthropicContentBlock::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse("");
        if (content.isBlank()) {
            throw new IllegalStateException("Anthropic-compatible LLM API returned an empty text block.");
        }
        return content.trim();
    }

    private String streamOpenAiCompatible(
            String systemPrompt,
            String userPrompt,
            double temperature,
            int maxTokens,
            Consumer<String> onDelta
    ) {
        return openAiRestClient.post()
                .uri("/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer " + llm.apiKey())
                .body(new ResponsesApiRequest(
                        llm.model(),
                        List.of(
                                new ResponsesInputMessage("system", systemPrompt),
                                new ResponsesInputMessage("user", userPrompt)
                        ),
                        temperature,
                        maxTokens,
                        true
                ))
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw new IllegalStateException("LLM streaming API returned " + response.getStatusCode());
                    }
                    String answer = readOpenAiStream(response.getBody(), onDelta);
                    if (answer.isBlank()) {
                        throw new IllegalStateException("LLM streaming API returned an empty message.");
                    }
                    return answer.trim();
                });
    }

    private String streamAnthropicCompatible(
            String systemPrompt,
            String userPrompt,
            int maxTokens,
            Consumer<String> onDelta
    ) {
        return anthropicRestClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("x-api-key", llm.apiKey())
                .header("anthropic-version", "2023-06-01")
                .body(new AnthropicMessageRequest(
                        llm.model(),
                        systemPrompt,
                        maxTokens,
                        List.of(new AnthropicMessage("user", userPrompt)),
                        true
                ))
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw new IllegalStateException("Anthropic-compatible streaming API returned " + response.getStatusCode());
                    }
                    String answer = readAnthropicStream(response.getBody(), onDelta);
                    if (answer.isBlank()) {
                        throw new IllegalStateException("Anthropic-compatible streaming API returned an empty message.");
                    }
                    return answer.trim();
                });
    }

    private String readOpenAiStream(java.io.InputStream body, Consumer<String> onDelta) throws IOException {
        StringBuilder answer = new StringBuilder();
        boolean truncated = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if (data.isBlank() || "[DONE]".equals(data)) {
                    continue;
                }
                JsonNode root = objectMapper.readTree(data);
                String type = root.path("type").asText();
                JsonNode deltaNode = "response.output_text.delta".equals(type)
                        ? root.path("delta")
                        : root.path("choices").path(0).path("delta").path("content");
                if (deltaNode.isTextual() && !deltaNode.asText().isEmpty()) {
                    String delta = deltaNode.asText();
                    answer.append(delta);
                    if (onDelta != null) {
                        onDelta.accept(delta);
                    }
                }
                JsonNode incompleteReason = root.path("response").path("incomplete_details").path("reason");
                JsonNode finishReason = root.path("choices").path(0).path("finish_reason");
                if ((incompleteReason.isTextual() && "max_output_tokens".equals(incompleteReason.asText()))
                        || (finishReason.isTextual() && "length".equals(finishReason.asText()))) {
                    truncated = true;
                }
            }
        }
        if (truncated) {
            String notice = "\n\n[提示：答案因输出长度限制被截断，请回复“继续”以补全剩余内容。]";
            answer.append(notice);
            if (onDelta != null) {
                onDelta.accept(notice);
            }
        }
        return answer.toString();
    }

    private String readAnthropicStream(java.io.InputStream body, Consumer<String> onDelta) throws IOException {
        StringBuilder answer = new StringBuilder();
        boolean truncated = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if (data.isBlank() || "[DONE]".equals(data)) {
                    continue;
                }
                JsonNode root = objectMapper.readTree(data);
                String type = root.path("type").asText();
                if ("content_block_delta".equals(type)) {
                    JsonNode text = root.path("delta").path("text");
                    if (text.isTextual() && !text.asText().isEmpty()) {
                        String delta = text.asText();
                        answer.append(delta);
                        if (onDelta != null) {
                            onDelta.accept(delta);
                        }
                    }
                } else if ("message_delta".equals(type)) {
                    JsonNode stopReason = root.path("delta").path("stop_reason");
                    if (stopReason.isTextual() && "max_tokens".equals(stopReason.asText())) {
                        truncated = true;
                    }
                }
            }
        }
        if (truncated) {
            String notice = "\n\n[提示：答案因输出长度限制被截断，请回复“继续”以补全剩余内容。]";
            answer.append(notice);
            if (onDelta != null) {
                onDelta.accept(notice);
            }
        }
        return answer.toString();
    }

    private String extractResponsesApiText(ResponsesApiResponse response) {
        if (response == null || response.output() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ResponsesOutputItem item : response.output()) {
            if (item == null || item.content() == null) {
                continue;
            }
            for (ResponsesContentBlock block : item.content()) {
                if (block != null && block.text() != null && !block.text().isBlank()) {
                    text.append(block.text());
                }
            }
        }
        return text.toString();
    }

    private record ResponsesApiRequest(
            String model,
            List<ResponsesInputMessage> input,
            double temperature,
            int max_output_tokens,
            boolean stream
    ) {
    }

    private record ResponsesInputMessage(String role, String content) {
    }

    private record ResponsesApiResponse(List<ResponsesOutputItem> output) {
    }

    private record ResponsesOutputItem(List<ResponsesContentBlock> content) {
    }

    private record ResponsesContentBlock(String type, String text) {
    }

    private record AnthropicMessageRequest(
            String model,
            String system,
            int max_tokens,
            List<AnthropicMessage> messages,
            boolean stream
    ) {
    }

    private record AnthropicMessage(String role, String content) {
    }

    private record AnthropicMessageResponse(List<AnthropicContentBlock> content) {
    }

    private record AnthropicContentBlock(String type, String text) {
    }
}
