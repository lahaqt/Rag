package com.example.ragagent.client;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.service.LlmGateway;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LlmChatClient implements LlmGateway {
    private final RestClient openAiRestClient;
    private final RestClient anthropicRestClient;
    private final RagProperties.Llm llm;

    public LlmChatClient(RagProperties properties) {
        this.llm = properties.llm();
        this.openAiRestClient = RestClient.builder()
                .baseUrl(llm.openaiCompatible().baseUrl())
                .build();
        this.anthropicRestClient = RestClient.builder()
                .baseUrl(llm.anthropicCompatible().baseUrl())
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

    private String completeOpenAiCompatible(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        ChatCompletionResponse response = openAiRestClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + llm.apiKey())
                .body(new ChatCompletionRequest(
                        llm.model(),
                        List.of(
                                new ChatMessageRequest("system", systemPrompt),
                                new ChatMessageRequest("user", userPrompt)
                        ),
                        temperature,
                        maxTokens
                ))
                .retrieve()
                .body(ChatCompletionResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("LLM API returned an empty response.");
        }

        ChatMessageResponse message = response.choices().get(0).message();
        if (message == null || message.content() == null || message.content().isBlank()) {
            throw new IllegalStateException("LLM API returned an empty message.");
        }
        return message.content().trim();
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
                        List.of(new AnthropicMessage("user", userPrompt))
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

    private record ChatCompletionRequest(
            String model,
            List<ChatMessageRequest> messages,
            double temperature,
            int max_tokens
    ) {
    }

    private record ChatMessageRequest(String role, String content) {
    }

    private record ChatCompletionResponse(List<ChatChoice> choices) {
    }

    private record ChatChoice(ChatMessageResponse message) {
    }

    private record ChatMessageResponse(String role, String content) {
    }

    private record AnthropicMessageRequest(
            String model,
            String system,
            int max_tokens,
            List<AnthropicMessage> messages
    ) {
    }

    private record AnthropicMessage(String role, String content) {
    }

    private record AnthropicMessageResponse(List<AnthropicContentBlock> content) {
    }

    private record AnthropicContentBlock(String type, String text) {
    }
}
