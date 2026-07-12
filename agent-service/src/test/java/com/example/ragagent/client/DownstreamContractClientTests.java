package com.example.ragagent.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatMessage;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.dto.VectorSearchMatch;
import com.example.ragagent.dto.VectorSearchRequest;
import com.example.ragagent.dto.VectorSearchResponse;
import com.example.ragagent.observability.TraceContextProvider;
import com.example.ragagent.observability.TraceContextSnapshot;
import com.example.ragagent.observability.TracePropagationInterceptor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class DownstreamContractClientTests {
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> requestBody = new AtomicReference<>("");
    private final AtomicReference<String> authorization = new AtomicReference<>("");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void queryAnalysisClientUsesTheV1AnalyzeContract() {
        server.createContext("/api/chat/analyze", exchange -> respond(exchange, """
                {"sessionId":"conversation-1","knowledgeBaseId":"kb-1","originalQuery":"refund",
                "normalizedQuery":"refund","rewrittenQuery":"refund policy","intent":"knowledge",
                "confidence":0.91,"route":"knowledge_retrieval","needsRewrite":true,"rewritten":true,
                "historyLength":1,"retrievalQueries":["refund policy"],"requestType":"USER_QUESTION",
                "executionMode":"SINGLE_TOOL","requiredCapabilities":["rag_retrieval"],
                "clarificationQuestion":"","slots":{},"systemCommand":"","reasons":["contract-test"]}
                """));

        HttpQueryAnalysisClient client = new HttpQueryAnalysisClient(
                properties(), RestClient.builder(), traceInterceptor()
        );

        QueryAnalysisResponse response = client.analyze(new ChatRequest(
                "refund", "kb-1", "conversation-1", List.of(new ChatMessage("user", "refund")), null
        ));

        assertThat(response.route()).isEqualTo("knowledge_retrieval");
        assertThat(response.safeRequiredCapabilities()).containsExactly("rag_retrieval");
        assertThat(requestBody.get()).contains("\"query\":\"refund\"");
        assertThat(requestBody.get()).contains("\"sessionId\":\"conversation-1\"");
    }

    @Test
    void retrievalClientUsesTheV1SearchContract() {
        server.createContext("/api/vector/search", exchange -> respond(exchange, """
                {"matches":[{"knowledgeBaseId":"kb-1","documentId":"doc-1","chunkId":"chunk-1",
                "chunkIndex":0,"documentName":"refund.md","content":"Refund evidence","score":0.95}]}
                """));

        HttpStorageRetrievalClient client = new HttpStorageRetrievalClient(
                properties(), RestClient.builder(), traceInterceptor()
        );

        VectorSearchResponse response = client.search(new VectorSearchRequest(
                "kb-1", "refund policy", 6, 0.0, "hybrid", true, 4
        ));

        assertThat(response.safeMatches()).singleElement()
                .satisfies(match -> assertThat(match.chunkId()).isEqualTo("chunk-1"));
        assertThat(requestBody.get()).contains("\"knowledgeBaseId\":\"kb-1\"");
        assertThat(requestBody.get()).contains("\"retrievalMode\":\"hybrid\"");
    }

    @Test
    void rerankerClientUsesSiliconFlowIndexesAndScores() {
        server.createContext("/rerank", exchange -> respond(exchange, """
                {"id":"rerank-test","results":[
                  {"index":1,"relevance_score":0.97},
                  {"index":0,"relevance_score":0.42}
                ]}
                """));
        SiliconFlowRerankerClient client = new SiliconFlowRerankerClient(
                rerankerProperties(), RestClient.builder(), traceInterceptor()
        );

        List<VectorSearchMatch> results = client.rerank("refund policy", List.of(
                match("chunk-1", "Less relevant content", 0.9),
                match("chunk-2", "Refund policy eligibility", 0.8)
        ), 2);

        assertThat(results).extracting(VectorSearchMatch::chunkId).containsExactly("chunk-2", "chunk-1");
        assertThat(results).extracting(VectorSearchMatch::score).containsExactly(0.97, 0.42);
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(requestBody.get()).contains("\"model\":\"Qwen/Qwen3-Reranker-8B\"");
        assertThat(requestBody.get()).contains("\"top_n\":2");
        assertThat(requestBody.get()).contains("\"return_documents\":false");
    }

    private RagProperties properties() {
        return new RagProperties(
                null,
                new RagProperties.Downstream(baseUrl, baseUrl, 2),
                null, null, null, null, null, null, null, null
        );
    }

    private RagProperties rerankerProperties() {
        RagProperties.Reranker reranker = new RagProperties.Reranker(
                true, baseUrl, "test-key", "Qwen/Qwen3-Reranker-8B", 20, "rank accurately", 2
        );
        return new RagProperties(
                null,
                null,
                new RagProperties.Retrieval(6, 0.0, "hybrid", true, 4, reranker),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private VectorSearchMatch match(String chunkId, String content, double score) {
        return new VectorSearchMatch("kb-1", "doc-1", chunkId, 0, "refund.md", content, score);
    }

    private TracePropagationInterceptor traceInterceptor() {
        TraceContextProvider provider = mock(TraceContextProvider.class);
        when(provider.current()).thenReturn(TraceContextSnapshot.empty());
        return new TracePropagationInterceptor(provider);
    }

    private void respond(HttpExchange exchange, String json) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
