package com.example.ragagent.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        Cors cors,
        Downstream downstream,
        Retrieval retrieval,
        Prompt prompt,
        Llm llm,
        Tools tools
) {
    public RagProperties {
        if (cors == null) {
            cors = new Cors(List.of("http://127.0.0.1:5173", "http://localhost:5173"));
        }
        if (downstream == null) {
            downstream = new Downstream("http://127.0.0.1:28082", "http://127.0.0.1:28081");
        }
        if (retrieval == null) {
            retrieval = new Retrieval(6, 0.0, "hybrid", true, 4);
        }
        if (prompt == null) {
            prompt = new Prompt(8000);
        }
        if (llm == null) {
            llm = new Llm(
                    "volcengine-ark",
                    "ark-code-latest",
                    "",
                    0.2,
                    1200,
                    new CompatibleEndpoint("https://ark.cn-beijing.volces.com/api/coding/v3"),
                    new CompatibleEndpoint("https://ark.cn-beijing.volces.com/api/coding")
            );
        }
        if (tools == null) {
            tools = new Tools(new WebSearch("bing", "https://cn.bing.com", 5));
        }
    }

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            if (allowedOrigins == null || allowedOrigins.isEmpty()) {
                allowedOrigins = List.of("http://127.0.0.1:5173", "http://localhost:5173");
            } else {
                allowedOrigins = List.copyOf(allowedOrigins);
            }
        }
    }

    public record Downstream(String queryRewriteBaseUrl, String storageBaseUrl) {
        public Downstream {
            if (queryRewriteBaseUrl == null || queryRewriteBaseUrl.isBlank()) {
                queryRewriteBaseUrl = "http://127.0.0.1:28082";
            }
            if (storageBaseUrl == null || storageBaseUrl.isBlank()) {
                storageBaseUrl = "http://127.0.0.1:28081";
            }
        }
    }

    public record Retrieval(
            Integer topK,
            Double similarityThreshold,
            String retrievalMode,
            Boolean queryExpansionEnabled,
            Integer queryExpansionCount
    ) {
        public Retrieval {
            topK = topK == null ? 6 : Math.max(1, Math.min(topK, 20));
            similarityThreshold = similarityThreshold == null ? 0.0 : Math.max(0.0, Math.min(similarityThreshold, 1.0));
            retrievalMode = retrievalMode == null || retrievalMode.isBlank() ? "hybrid" : retrievalMode;
            queryExpansionEnabled = queryExpansionEnabled == null || queryExpansionEnabled;
            queryExpansionCount = queryExpansionCount == null ? 4 : Math.max(1, Math.min(queryExpansionCount, 5));
        }
    }

    public record Prompt(Integer maxContextCharacters) {
        public Prompt {
            maxContextCharacters = maxContextCharacters == null ? 8000 : Math.max(1000, maxContextCharacters);
        }
    }

    public record Llm(
            String provider,
            String model,
            String apiKey,
            Double temperature,
            Integer maxTokens,
            CompatibleEndpoint openaiCompatible,
            CompatibleEndpoint anthropicCompatible
    ) {
        public Llm {
            if (provider == null || provider.isBlank()) {
                provider = "volcengine-ark";
            }
            if (model == null || model.isBlank()) {
                model = "ark-code-latest";
            }
            if (apiKey == null) {
                apiKey = "";
            }
            temperature = temperature == null ? 0.2 : Math.max(0.0, Math.min(temperature, 1.5));
            maxTokens = maxTokens == null ? 1200 : Math.max(128, maxTokens);
            if (openaiCompatible == null) {
                openaiCompatible = new CompatibleEndpoint("https://ark.cn-beijing.volces.com/api/coding/v3");
            }
            if (anthropicCompatible == null) {
                anthropicCompatible = new CompatibleEndpoint("https://ark.cn-beijing.volces.com/api/coding");
            }
        }
    }

    public record CompatibleEndpoint(String baseUrl) {
        public CompatibleEndpoint {
            if (baseUrl == null) {
                baseUrl = "";
            }
        }
    }

    public record Tools(WebSearch webSearch) {
        public Tools {
            if (webSearch == null) {
                webSearch = new WebSearch("bing", "https://cn.bing.com", 5);
            }
        }
    }

    public record WebSearch(String provider, String baseUrl, Integer maxResults) {
        public WebSearch {
            if (provider == null || provider.isBlank()) {
                provider = "bing";
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://cn.bing.com";
            }
            maxResults = maxResults == null ? 5 : Math.max(1, Math.min(maxResults, 10));
        }
    }
}
