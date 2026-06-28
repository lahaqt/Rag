package com.example.ragagent.a2a;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.service.MultiAgentOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class A2aController {
    private static final String JSON_RPC_VERSION = "2.0";
    private static final String SEND_MESSAGE_METHOD = "message/send";

    private final A2aAgentRegistry agentRegistry;
    private final MultiAgentOrchestrator multiAgentOrchestrator;
    private final ObjectMapper objectMapper;

    public A2aController(
            A2aAgentRegistry agentRegistry,
            MultiAgentOrchestrator multiAgentOrchestrator,
            ObjectMapper objectMapper
    ) {
        this.agentRegistry = agentRegistry;
        this.multiAgentOrchestrator = multiAgentOrchestrator;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/.well-known/agent.json")
    public A2aAgentCard agentCard() {
        return agentRegistry.orchestratorCard();
    }

    @GetMapping("/api/chat/multi-agent/agents")
    public List<A2aAgentCard> specialistAgentCards() {
        return agentRegistry.cards();
    }

    @PostMapping("/api/chat/multi-agent/a2a")
    public ResponseEntity<A2aJsonRpcResponse> messageSend(@RequestBody A2aJsonRpcRequest request) {
        if (request == null || !JSON_RPC_VERSION.equals(request.jsonrpc())) {
            return jsonRpcError(request == null ? null : request.id(), -32600, "Invalid JSON-RPC request", HttpStatus.BAD_REQUEST);
        }
        if (!SEND_MESSAGE_METHOD.equals(request.method())) {
            return jsonRpcError(request.id(), -32601, "Unsupported A2A method: " + request.method(), HttpStatus.NOT_FOUND);
        }

        A2aSendMessageParams params = objectMapper.convertValue(request.params(), A2aSendMessageParams.class);
        if (params == null || params.message() == null) {
            return jsonRpcError(request.id(), -32602, "A2A message is required", HttpStatus.BAD_REQUEST);
        }
        String query = text(params.message());
        if (query.isBlank()) {
            return jsonRpcError(request.id(), -32602, "A2A message text part must not be blank", HttpStatus.BAD_REQUEST);
        }

        A2aTask task = multiAgentOrchestrator.answerTask(new ChatRequest(
                query,
                stringData(params.message(), "knowledgeBaseId"),
                params.message().contextId(),
                List.of(),
                null
        ));
        return ResponseEntity.ok(new A2aJsonRpcResponse(JSON_RPC_VERSION, request.id(), task, null));
    }

    private ResponseEntity<A2aJsonRpcResponse> jsonRpcError(Object id, int code, String message, HttpStatus status) {
        return ResponseEntity.status(status)
                .body(new A2aJsonRpcResponse(JSON_RPC_VERSION, id, null, new A2aJsonRpcError(code, message)));
    }

    private String text(A2aMessage message) {
        return message.parts().stream()
                .filter(part -> "text".equals(part.kind()))
                .map(A2aPart::text)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String stringData(A2aMessage message, String key) {
        return message.parts().stream()
                .filter(part -> "data".equals(part.kind()))
                .map(A2aPart::data)
                .map(data -> data.get(key))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .findFirst()
                .orElse(null);
    }

    public record A2aJsonRpcRequest(
            String jsonrpc,
            Object id,
            String method,
            Map<String, Object> params
    ) {
    }

    public record A2aSendMessageParams(A2aMessage message) {
    }

    public record A2aJsonRpcResponse(
            String jsonrpc,
            Object id,
            Object result,
            A2aJsonRpcError error
    ) {
    }

    public record A2aJsonRpcError(int code, String message) {
    }
}
