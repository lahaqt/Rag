package com.example.authoringcoach.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "authoring")
public record AuthoringProperties(
        Downstream downstream,
        Review review,
        Llm llm,
        Mcp mcp,
        ServiceClient serviceClient,
        Cors cors
) {
    public AuthoringProperties {
        downstream = downstream == null ? new Downstream(null, null) : downstream;
        review = review == null ? new Review(null, null, null, null) : review;
        llm = llm == null ? new Llm(null, null, null, null, null, null, null) : llm;
        mcp = mcp == null ? new Mcp(null, null, null) : mcp;
        serviceClient = serviceClient == null ? new ServiceClient(null, null, null, null) : serviceClient;
        cors = cors == null ? new Cors(null) : cors;
    }

    public record Downstream(String contentBaseUrl, Integer contentTimeoutSeconds) {
        public Downstream {
            contentBaseUrl = blank(contentBaseUrl) ? "http://127.0.0.1:28081" : contentBaseUrl;
            contentTimeoutSeconds = contentTimeoutSeconds == null ? 20 : Math.max(2, contentTimeoutSeconds);
        }
    }

    public record Review(Integer maxReflectionRetries, Integer maxExecutionSeconds,
                         Integer evidenceLimit, Integer workerDelayMillis) {
        public Review {
            maxReflectionRetries = maxReflectionRetries == null ? 2 : Math.max(0, maxReflectionRetries);
            maxExecutionSeconds = maxExecutionSeconds == null ? 30 : Math.max(5, maxExecutionSeconds);
            evidenceLimit = evidenceLimit == null ? 6 : Math.max(1, evidenceLimit);
            workerDelayMillis = workerDelayMillis == null ? 500 : Math.max(100, workerDelayMillis);
        }
    }

    public record Llm(String provider, String model, String apiKey, Double temperature, Integer maxTokens,
                      CompatibleEndpoint openaiCompatible, CompatibleEndpoint anthropicCompatible) {
        public Llm {
            provider = blank(provider) ? "openai-compatible" : provider;
            model = blank(model) ? "" : model;
            apiKey = apiKey == null ? "" : apiKey;
            temperature = temperature == null ? 0.2 : temperature;
            maxTokens = maxTokens == null ? 2400 : Math.max(256, maxTokens);
            openaiCompatible = openaiCompatible == null ? new CompatibleEndpoint("") : openaiCompatible;
            anthropicCompatible = anthropicCompatible == null ? new CompatibleEndpoint("") : anthropicCompatible;
        }
    }

    public record CompatibleEndpoint(String baseUrl) {
        public CompatibleEndpoint { baseUrl = baseUrl == null ? "" : baseUrl; }
    }

    public record ServiceClient(String tokenUri, String clientId, String clientSecret, String audience) {
        public ServiceClient {
            tokenUri = blank(tokenUri)
                    ? "http://127.0.0.1:28090/realms/authoring/protocol/openid-connect/token" : tokenUri;
            clientId = blank(clientId) ? "authoring-service" : clientId;
            clientSecret = clientSecret == null ? "" : clientSecret;
            audience = blank(audience) ? "content-service" : audience;
        }
    }

    public record Mcp(Boolean enabled, Integer timeoutSeconds, List<McpServer> servers) {
        public Mcp {
            enabled = enabled != null && enabled;
            timeoutSeconds = timeoutSeconds == null ? 8 : Math.max(2, Math.min(timeoutSeconds, 60));
            servers = servers == null ? List.of() : List.copyOf(servers);
        }
    }

    public record McpServer(String id, String name, String transport, String endpoint, String command,
                            List<String> args, Map<String, String> environment, String workingDirectory,
                            String bearerToken, Boolean enabled, Boolean readOnly) {
        public McpServer {
            id = id == null ? "" : id;
            name = blank(name) ? id : name;
            command = command == null ? "" : command;
            transport = blank(transport) ? (command.isBlank() ? "streamable_http" : "stdio") : transport;
            endpoint = endpoint == null ? "" : endpoint;
            args = args == null ? List.of() : List.copyOf(args);
            environment = environment == null ? Map.of() : Map.copyOf(environment);
            workingDirectory = workingDirectory == null ? "" : workingDirectory;
            bearerToken = bearerToken == null ? "" : bearerToken;
            enabled = enabled == null || enabled;
            readOnly = readOnly != null && readOnly;
        }
    }

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null || allowedOrigins.isEmpty()
                    ? List.of("http://127.0.0.1:5173", "http://localhost:5173")
                    : List.copyOf(allowedOrigins);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
