package com.example.ragagent.a2a;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.service.AgentToolResult;
import com.example.ragagent.service.SpecialistAgent;
import com.example.ragagent.service.SpecialistAgentResult;
import com.example.ragagent.service.ToolDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RemoteA2aAgentTransport implements A2aAgentTransport {
    private static final String JSON_RPC_VERSION = "2.0";

    private final RagProperties properties;
    private final ObjectMapper objectMapper;

    public RemoteA2aAgentTransport(RagProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String agentName) {
        return remoteAgent(agentName).isPresent();
    }

    @Override
    public String name() {
        return "remote-a2a";
    }

    @Override
    public SpecialistAgentResult execute(
            SpecialistAgent targetAgent,
            A2aMessage message,
            ChatRequest request,
            QueryAnalysisResponse analysis,
            int startStep,
            Duration timeout
    ) {
        RagProperties.RemoteAgent remoteAgent = remoteAgent(targetAgent.name())
                .orElseThrow(() -> new IllegalArgumentException("No remote A2A agent configured for " + targetAgent.name()));
        try {
            Duration requestTimeout = remoteAgent.timeoutSeconds() == null
                    ? timeout
                    : Duration.ofSeconds(remoteAgent.timeoutSeconds());
            A2aTask task = send(remoteAgent, message, requestTimeout);
            boolean success = task.status() == null
                    || task.status().state() == null
                    || task.status().state() != A2aTaskState.FAILED;
            String observation = taskObservation(task);
            AgentToolResult toolResult = new AgentToolResult(
                    "multi_agent",
                    request.query(),
                    success,
                    observation,
                    "remote_a2a_" + (task.status() == null ? "completed" : task.status().state().name().toLowerCase()),
                    List.of(),
                    List.of()
            );
            return new SpecialistAgentResult(
                    targetAgent.name(),
                    new ToolDecision(true, "multi_agent", request.query(), "remote_a2a_agent:" + targetAgent.name()),
                    toolResult,
                    task,
                    List.of(new AgentTraceStep(
                            startStep,
                            "a2a_remote",
                            analysis.route(),
                            targetAgent.name(),
                            "message_send",
                            "endpoint=" + remoteAgent.endpoint() + "; taskId=" + task.id()
                    ))
            );
        } catch (Exception exception) {
            AgentToolResult failure = AgentToolResult.failure(
                    "multi_agent",
                    request.query(),
                    "remote_a2a_failed: " + exception.getMessage(),
                    "remote_a2a_failed"
            );
            return new SpecialistAgentResult(
                    targetAgent.name(),
                    new ToolDecision(true, "multi_agent", request.query(), "remote_a2a_failed:" + targetAgent.name()),
                    failure,
                    null,
                    List.of(AgentTraceStep.failed(
                            startStep,
                            "a2a_remote",
                            analysis.route(),
                            targetAgent.name(),
                            "message_send",
                            exception.getMessage(),
                            0,
                            exception
                    ))
            );
        }
    }

    private A2aTask send(RagProperties.RemoteAgent remoteAgent, A2aMessage message, Duration timeout) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", JSON_RPC_VERSION);
        body.put("id", "rpc-" + UUID.randomUUID());
        body.put("method", "message/send");
        body.put("params", Map.of("message", message));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(remoteAgent.endpoint()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (!remoteAgent.bearerToken().isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + remoteAgent.bearerToken());
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Remote A2A returned HTTP " + response.statusCode());
        }
        A2aJsonRpcResponse rpcResponse = objectMapper.readValue(response.body(), A2aJsonRpcResponse.class);
        if (rpcResponse.error() != null) {
            throw new IllegalStateException("Remote A2A error " + rpcResponse.error().code() + ": " + rpcResponse.error().message());
        }
        return objectMapper.convertValue(rpcResponse.result(), A2aTask.class);
    }

    private Optional<RagProperties.RemoteAgent> remoteAgent(String agentName) {
        if (properties.multiAgent() == null || properties.multiAgent().remoteAgents() == null) {
            return Optional.empty();
        }
        return properties.multiAgent().remoteAgents().stream()
                .filter(RagProperties.RemoteAgent::enabled)
                .filter(remote -> remote.id().equals(agentName))
                .filter(remote -> !remote.endpoint().isBlank())
                .findFirst();
    }

    private String taskObservation(A2aTask task) {
        if (task == null) {
            return "";
        }
        if (task.status() != null && task.status().message() != null) {
            String text = text(task.status().message());
            if (!text.isBlank()) {
                return text;
            }
        }
        return task.artifacts().stream()
                .flatMap(artifact -> artifact.parts().stream())
                .filter(part -> "text".equals(part.kind()))
                .map(A2aPart::text)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("remote_task_state=" + (task.status() == null ? "unknown" : task.status().state()));
    }

    private String text(A2aMessage message) {
        return message.parts().stream()
                .filter(part -> "text".equals(part.kind()))
                .map(A2aPart::text)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private record A2aJsonRpcResponse(String jsonrpc, Object id, Object result, A2aJsonRpcError error) {
    }

    private record A2aJsonRpcError(int code, String message) {
    }
}
