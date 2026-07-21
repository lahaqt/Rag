package com.example.ragagent.service;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.observability.TracePropagationInterceptor;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Component
public class LlmChatClient {
    private final RestClient openAiRestClient;
    private final RestClient anthropicRestClient;
    private final RagProperties.Llm llm;

    public LlmChatClient(RagProperties properties) {
        this(properties, RestClient.builder(), null);
    }

    @Autowired
    public LlmChatClient(
            RagProperties properties,
            RestClient.Builder restClientBuilder,
            TracePropagationInterceptor tracePropagationInterceptor
    ) {
        this.llm = properties.llm();
        RestClient.Builder openAiBuilder = restClientBuilder.clone()
                .baseUrl(llm.openaiCompatible().baseUrl())
                .requestFactory(requestFactory(llm.timeoutSeconds()));
        RestClient.Builder anthropicBuilder = restClientBuilder.clone()
                .baseUrl(llm.anthropicCompatible().baseUrl())
                .requestFactory(requestFactory(llm.timeoutSeconds()));
        if (tracePropagationInterceptor != null) {
            openAiBuilder.requestInterceptor(tracePropagationInterceptor);
            anthropicBuilder.requestInterceptor(tracePropagationInterceptor);
        }
        this.openAiRestClient = openAiBuilder.build();
        this.anthropicRestClient = anthropicBuilder.build();
    }

    private SimpleClientHttpRequestFactory requestFactory(int timeoutSeconds) {
        int timeoutMillis = timeoutSeconds * 1_000;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMillis);
        factory.setReadTimeout(timeoutMillis);
        return factory;
    }

    public boolean isConfigured() {
        return llm != null
                && llm.apiKey() != null
                && !llm.apiKey().isBlank()
                && ((llm.openaiCompatible() != null && !llm.openaiCompatible().baseUrl().isBlank())
                || (llm.anthropicCompatible() != null && !llm.anthropicCompatible().baseUrl().isBlank()));
    }

    public String complete(String prompt, double temperature, int maxTokens) {
        if (!isConfigured()) {
            throw new IllegalStateException("rag.llm.api-key is required for LLM chat completion.");
        }

        try {
            return completeOpenAiCompatible(prompt, temperature, maxTokens);
        } catch (Exception openAiException) {
            if (llm.anthropicCompatible() == null || llm.anthropicCompatible().baseUrl().isBlank()) {
                throw openAiException;
            }
            return completeAnthropicCompatible(prompt, maxTokens);
        }
    }

    private String completeOpenAiCompatible(String prompt, double temperature, int maxTokens) {
        ResponsesApiResponse response = openAiRestClient.post()
                .uri("/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + llm.apiKey())
                .body(new ResponsesApiRequest(
                        llm.model(),
                        List.of(new ResponsesInputMessage("user", prompt)),
                        temperature,
                        maxTokens
                ))
                .retrieve()
                .body(ResponsesApiResponse.class);

        String content = extractResponsesApiText(response);
        if (content.isBlank()) {
            throw new IllegalStateException("LLM API returned an empty response.");
        }
        return content.trim();
    }

    private String completeAnthropicCompatible(String prompt, int maxTokens) {
        AnthropicMessageResponse response = anthropicRestClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", llm.apiKey())
                .header("anthropic-version", "2023-06-01")
                .body(new AnthropicMessageRequest(
                        llm.model(),
                        maxTokens,
                        List.of(new AnthropicMessage("user", prompt))
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
            int max_output_tokens
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
