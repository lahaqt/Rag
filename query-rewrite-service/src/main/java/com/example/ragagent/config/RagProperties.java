package com.example.ragagent.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        Cors cors,
        Llm llm
) {
    public RagProperties {
        if (cors == null) {
            cors = new Cors(List.of("http://127.0.0.1:5173", "http://localhost:5173"));
        }
        if (llm == null) {
            llm = new Llm(
                    "volcengine-ark",
                    "ark-code-latest",
                    "",
                    new CompatibleEndpoint("https://ark.cn-beijing.volces.com/api/coding/v3"),
                    new CompatibleEndpoint("https://ark.cn-beijing.volces.com/api/coding")
            );
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

    public record Llm(
            String provider,
            String model,
            String apiKey,
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
}
