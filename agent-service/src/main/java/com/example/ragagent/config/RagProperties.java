package com.example.ragagent.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        Cors cors,
        Downstream downstream,
        Retrieval retrieval,
        Prompt prompt,
        Llm llm,
        Tools tools,
        Mcp mcp,
        Agent agent,
        MultiAgent multiAgent,
        Memory memory
) {
    @ConstructorBinding
    public RagProperties {
        if (cors == null) {
            cors = new Cors(List.of("http://127.0.0.1:5173", "http://localhost:5173"));
        }
        if (downstream == null) {
            downstream = new Downstream("http://127.0.0.1:28082", "http://127.0.0.1:28081", 8);
        }
        if (retrieval == null) {
            retrieval = new Retrieval(6, 0.0, "hybrid", true, 4, null);
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
                    new CompatibleEndpoint("https://ark.cn-beijing.volces.com/api/plan/v3"),
                    new CompatibleEndpoint("https://ark.cn-beijing.volces.com/api/plan")
            );
        }
        if (tools == null) {
            tools = new Tools(new WebSearch("bing", "https://cn.bing.com", 5));
        }
        if (mcp == null) {
            mcp = new Mcp(false, 8, List.of());
        }
        if (agent == null) {
            agent = new Agent(4, 2, true, List.of("web_search", "mcp_tool", "rag_retrieval"), 8, 2, 100, 45);
        }
        if (multiAgent == null) {
            multiAgent = new MultiAgent(4, 12, true);
        }
        if (memory == null) {
            memory = new Memory("in-memory", true, 8, 12, 1600, 16, 86400L, "window", 4, true, null);
        }
    }

    public RagProperties(
            Cors cors,
            Downstream downstream,
            Retrieval retrieval,
            Prompt prompt,
            Llm llm,
            Tools tools,
            Mcp mcp,
            Agent agent,
            Memory memory
    ) {
        this(cors, downstream, retrieval, prompt, llm, tools, mcp, agent, null, memory);
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

    public record Downstream(
            String queryRewriteBaseUrl,
            String storageBaseUrl,
            Integer queryAnalysisTimeoutSeconds
    ) {
        public Downstream {
            if (queryRewriteBaseUrl == null || queryRewriteBaseUrl.isBlank()) {
                queryRewriteBaseUrl = "http://127.0.0.1:28082";
            }
            if (storageBaseUrl == null || storageBaseUrl.isBlank()) {
                storageBaseUrl = "http://127.0.0.1:28081";
            }
            queryAnalysisTimeoutSeconds = queryAnalysisTimeoutSeconds == null
                    ? 8
                    : Math.max(1, Math.min(queryAnalysisTimeoutSeconds, 60));
        }

        public Downstream(String queryRewriteBaseUrl, String storageBaseUrl) {
            this(queryRewriteBaseUrl, storageBaseUrl, 8);
        }
    }

    public record Retrieval(
            Integer topK,
            Double similarityThreshold,
            String retrievalMode,
            Boolean queryExpansionEnabled,
            Integer queryExpansionCount,
            Reranker reranker
    ) {
        public Retrieval {
            topK = topK == null ? 6 : Math.max(1, Math.min(topK, 20));
            similarityThreshold = similarityThreshold == null ? 0.0 : Math.max(0.0, Math.min(similarityThreshold, 1.0));
            retrievalMode = retrievalMode == null || retrievalMode.isBlank() ? "hybrid" : retrievalMode;
            queryExpansionEnabled = queryExpansionEnabled == null || queryExpansionEnabled;
            queryExpansionCount = queryExpansionCount == null ? 4 : Math.max(1, Math.min(queryExpansionCount, 5));
            reranker = reranker == null ? new Reranker(false, null, null, null, null, null, null) : reranker;
        }

        public Retrieval(
                Integer topK,
                Double similarityThreshold,
                String retrievalMode,
                Boolean queryExpansionEnabled,
                Integer queryExpansionCount
        ) {
            this(topK, similarityThreshold, retrievalMode, queryExpansionEnabled, queryExpansionCount, null);
        }
    }

    public record Reranker(
            Boolean enabled,
            String baseUrl,
            String apiKey,
            String model,
            Integer candidateTopK,
            String instruction,
            Integer timeoutSeconds
    ) {
        public Reranker {
            enabled = enabled != null && enabled;
            baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.siliconflow.cn/v1" : baseUrl;
            apiKey = apiKey == null ? "" : apiKey;
            model = (model == null || model.isBlank()) ? "Qwen/Qwen3-Reranker-8B" : model;
            candidateTopK = candidateTopK == null ? 20 : Math.max(1, Math.min(candidateTopK, 50));
            instruction = (instruction == null || instruction.isBlank())
                    ? "根据用户问题重排文档，优先选择可直接、准确回答问题的内容。"
                    : instruction;
            timeoutSeconds = timeoutSeconds == null ? 8 : Math.max(1, Math.min(timeoutSeconds, 60));
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
            maxTokens = maxTokens == null ? 2400 : Math.max(128, maxTokens);
            if (openaiCompatible == null) {
                openaiCompatible = new CompatibleEndpoint("https://ark.cn-beijing.volces.com/api/plan/v3");
            }
            if (anthropicCompatible == null) {
                anthropicCompatible = new CompatibleEndpoint("https://ark.cn-beijing.volces.com/api/plan");
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

    public record Agent(
            Integer maxIterations,
            Integer maxReflectionRetries,
            Boolean plannerEnabled,
            List<String> capabilityPriority,
            Integer toolTimeoutSeconds,
            Integer readOnlyToolMaxAttempts,
            Integer retryBackoffMillis,
            Integer maxExecutionSeconds
    ) {
        public Agent {
            maxIterations = maxIterations == null ? 4 : Math.max(1, Math.min(maxIterations, 8));
            maxReflectionRetries = maxReflectionRetries == null
                    ? 2
                    : Math.max(0, Math.min(maxReflectionRetries, 4));
            plannerEnabled = plannerEnabled == null || plannerEnabled;
            List<String> normalizedPriority = capabilityPriority == null ? List.of() : capabilityPriority.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
            capabilityPriority = normalizedPriority.isEmpty()
                    ? List.of("web_search", "mcp_tool", "rag_retrieval")
                    : normalizedPriority;
            toolTimeoutSeconds = toolTimeoutSeconds == null ? 8 : Math.max(2, Math.min(toolTimeoutSeconds, 60));
            readOnlyToolMaxAttempts = readOnlyToolMaxAttempts == null
                    ? 2
                    : Math.max(1, Math.min(readOnlyToolMaxAttempts, 4));
            retryBackoffMillis = retryBackoffMillis == null ? 100 : Math.max(0, Math.min(retryBackoffMillis, 2000));
            maxExecutionSeconds = maxExecutionSeconds == null ? 45 : Math.max(2, Math.min(maxExecutionSeconds, 300));
        }

        public Agent(
                Integer maxIterations,
                Integer maxReflectionRetries,
                Boolean plannerEnabled,
                List<String> capabilityPriority
        ) {
            this(maxIterations, maxReflectionRetries, plannerEnabled, capabilityPriority, 8, 2, 100, 45);
        }
    }

    public record MultiAgent(
            Integer maxConcurrency,
            Integer timeoutSeconds,
            Boolean failureIsolationEnabled
    ) {
        public MultiAgent {
            maxConcurrency = maxConcurrency == null ? 4 : Math.max(1, Math.min(maxConcurrency, 16));
            timeoutSeconds = timeoutSeconds == null ? 12 : Math.max(2, Math.min(timeoutSeconds, 120));
            failureIsolationEnabled = failureIsolationEnabled == null || failureIsolationEnabled;
        }
    }

    public record Memory(
            String provider,
            Boolean enabled,
            Integer recentMessages,
            Integer summarizeAfterMessages,
            Integer summaryMaxCharacters,
            Integer stateMaxEntries,
            Long ttlSeconds,
            String summaryMode,
            Integer semanticMemoryMaxItems,
            Boolean profileEnabled,
            SemanticEmbedding semanticEmbedding
    ) {
        public record SemanticEmbedding(
                Boolean enabled,
                String provider,
                String baseUrl,
                String apiKey,
                String model,
                Integer dimensions,
                Double similarityThreshold
        ) {
            public SemanticEmbedding {
                enabled = enabled == null || enabled;
                provider = (provider == null || provider.isBlank()) ? "hash" : provider;
                baseUrl = baseUrl == null ? "" : baseUrl;
                apiKey = apiKey == null ? "" : apiKey;
                model = (model == null || model.isBlank()) ? provider : model;
                dimensions = dimensions == null ? 384 : Math.max(16, Math.min(dimensions, 4096));
                similarityThreshold = similarityThreshold == null
                        ? 0.20
                        : Math.max(0.0, Math.min(similarityThreshold, 1.0));
            }
        }

        public Memory(
                String provider,
                Boolean enabled,
                Integer recentMessages,
                Integer summarizeAfterMessages,
                Integer summaryMaxCharacters,
                Integer stateMaxEntries,
                Long ttlSeconds,
                String summaryMode
        ) {
            this(
                    provider,
                    enabled,
                    recentMessages,
                    summarizeAfterMessages,
                    summaryMaxCharacters,
                    stateMaxEntries,
                    ttlSeconds,
                    summaryMode,
                    4,
                    true,
                    null
            );
        }

        public Memory(
                String provider,
                Boolean enabled,
                Integer recentMessages,
                Integer summarizeAfterMessages,
                Integer summaryMaxCharacters,
                Integer stateMaxEntries,
                Long ttlSeconds
        ) {
            this(
                    provider,
                    enabled,
                    recentMessages,
                    summarizeAfterMessages,
                    summaryMaxCharacters,
                    stateMaxEntries,
                    ttlSeconds,
                    "window",
                    4,
                    true,
                    null
            );
        }

        public Memory(
                String provider,
                Boolean enabled,
                Integer recentMessages,
                Integer summarizeAfterMessages,
                Integer summaryMaxCharacters,
                Integer stateMaxEntries,
                Long ttlSeconds,
                String summaryMode,
                Integer semanticMemoryMaxItems,
                Boolean profileEnabled
        ) {
            this(
                    provider,
                    enabled,
                    recentMessages,
                    summarizeAfterMessages,
                    summaryMaxCharacters,
                    stateMaxEntries,
                    ttlSeconds,
                    summaryMode,
                    semanticMemoryMaxItems,
                    profileEnabled,
                    null
            );
        }

        @ConstructorBinding
        public Memory {
            provider = (provider == null || provider.isBlank()) ? "in-memory" : provider;
            enabled = enabled == null || enabled;
            recentMessages = recentMessages == null ? 8 : Math.max(2, Math.min(recentMessages, 30));
            summarizeAfterMessages = summarizeAfterMessages == null
                    ? 12
                    : Math.max(recentMessages + 2, Math.min(summarizeAfterMessages, 80));
            summaryMaxCharacters = summaryMaxCharacters == null
                    ? 1600
                    : Math.max(300, Math.min(summaryMaxCharacters, 6000));
            stateMaxEntries = stateMaxEntries == null ? 16 : Math.max(4, Math.min(stateMaxEntries, 64));
            ttlSeconds = ttlSeconds == null ? 86400L : Math.max(60L, Math.min(ttlSeconds, 604800L));
            summaryMode = (summaryMode == null || summaryMode.isBlank()) ? "window" : summaryMode;
            semanticMemoryMaxItems = semanticMemoryMaxItems == null
                    ? 4
                    : Math.max(0, Math.min(semanticMemoryMaxItems, 12));
            profileEnabled = profileEnabled == null || profileEnabled;
            semanticEmbedding = semanticEmbedding == null
                    ? new SemanticEmbedding(true, "hash", "", "", "hash", 384, 0.20)
                    : semanticEmbedding;
        }
    }

    public record Mcp(Boolean enabled, Integer timeoutSeconds, List<McpServer> servers) {
        public Mcp {
            enabled = enabled != null && enabled;
            timeoutSeconds = timeoutSeconds == null ? 8 : Math.max(2, Math.min(timeoutSeconds, 60));
            servers = servers == null ? List.of() : List.copyOf(servers);
        }
    }

    public record McpServer(
            String id,
            String name,
            String transport,
            String endpoint,
            String command,
            List<String> args,
            java.util.Map<String, String> environment,
            String workingDirectory,
            String bearerToken,
            Boolean enabled
    ) {
        public McpServer {
            if (id == null) {
                id = "";
            }
            if (name == null || name.isBlank()) {
                name = id;
            }
            if (transport == null || transport.isBlank()) {
                transport = command != null && !command.isBlank() ? "stdio" : "streamable_http";
            }
            if (endpoint == null) {
                endpoint = "";
            }
            if (command == null) {
                command = "";
            }
            args = args == null ? List.of() : List.copyOf(args);
            environment = environment == null ? java.util.Map.of() : java.util.Map.copyOf(environment);
            if (workingDirectory == null) {
                workingDirectory = "";
            }
            if (bearerToken == null) {
                bearerToken = "";
            }
            enabled = enabled == null || enabled;
        }
    }
}
