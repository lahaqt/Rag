package com.example.ragagent.a2a;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.service.MultiAgentOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class A2aController {
    private static final String JSON_RPC_VERSION = "2.0";
    private static final String SEND_MESSAGE_METHOD = "message/send";
    private static final String TASKS_GET_METHOD = "tasks/get";
    private static final String TASKS_CANCEL_METHOD = "tasks/cancel";

    private final A2aAgentRegistry agentRegistry;
    private final MultiAgentOrchestrator multiAgentOrchestrator;
    private final A2aTaskStore taskStore;
    private final ObjectMapper objectMapper;

    public A2aController(
            A2aAgentRegistry agentRegistry,
            MultiAgentOrchestrator multiAgentOrchestrator,
            A2aTaskStore taskStore,
            ObjectMapper objectMapper
    ) {
        this.agentRegistry = agentRegistry;
        this.multiAgentOrchestrator = multiAgentOrchestrator;
        this.taskStore = taskStore;
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

    @GetMapping("/api/chat/multi-agent/tasks/{taskId}")
    public ResponseEntity<A2aTask> task(@PathVariable String taskId) {
        return taskStore.find(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/chat/multi-agent/tasks/{taskId}/cancel")
    public ResponseEntity<A2aTask> cancelTask(@PathVariable String taskId) {
        return taskStore.find(taskId)
                .map(this::cancel)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/chat/multi-agent/a2a")
    public ResponseEntity<A2aJsonRpcResponse> messageSend(@RequestBody A2aJsonRpcRequest request) {
        if (request == null || !JSON_RPC_VERSION.equals(request.jsonrpc())) {
            return jsonRpcError(request == null ? null : request.id(), -32600, "Invalid JSON-RPC request", HttpStatus.BAD_REQUEST);
        }
        if (TASKS_GET_METHOD.equals(request.method())) {
            String taskId = taskId(request.params());
            return taskStore.find(taskId)
                    .map(task -> ResponseEntity.ok(new A2aJsonRpcResponse(JSON_RPC_VERSION, request.id(), task, null)))
                    .orElseGet(() -> jsonRpcError(request.id(), -32004, "A2A task not found: " + taskId, HttpStatus.NOT_FOUND));
        }
        if (TASKS_CANCEL_METHOD.equals(request.method())) {
            String taskId = taskId(request.params());
            return taskStore.find(taskId)
                    .map(this::cancel)
                    .map(task -> ResponseEntity.ok(new A2aJsonRpcResponse(JSON_RPC_VERSION, request.id(), task, null)))
                    .orElseGet(() -> jsonRpcError(request.id(), -32004, "A2A task not found: " + taskId, HttpStatus.NOT_FOUND));
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
        taskStore.save(task);
        return ResponseEntity.ok(new A2aJsonRpcResponse(JSON_RPC_VERSION, request.id(), task, null));
    }

    private A2aTask cancel(A2aTask task) {
        A2aMessage message = new A2aMessage(
                "agent",
                "msg-cancel-" + java.util.UUID.randomUUID(),
                task.contextId(),
                task.id(),
                List.of(task.id()),
                List.of(A2aPart.text("Task canceled."))
        );
        A2aTask canceled = new A2aTask(
                task.id(),
                task.contextId(),
                new A2aTaskStatus(A2aTaskState.CANCELED, message, java.time.Instant.now()),
                append(task.history(), message),
                task.artifacts()
        );
        return taskStore.save(canceled);
    }

    private List<A2aMessage> append(List<A2aMessage> history, A2aMessage message) {
        java.util.ArrayList<A2aMessage> next = new java.util.ArrayList<>(history == null ? List.of() : history);
        next.add(message);
        return List.copyOf(next);
    }

    private String taskId(Map<String, Object> params) {
        if (params == null) {
            return "";
        }
        Object value = params.get("taskId");
        if (value == null) {
            value = params.get("id");
        }
        return value instanceof String string ? string : "";
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
