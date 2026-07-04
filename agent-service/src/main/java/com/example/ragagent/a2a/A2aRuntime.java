package com.example.ragagent.a2a;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.service.AgentToolResult;
import com.example.ragagent.service.SpecialistAgent;
import com.example.ragagent.service.SpecialistAgentResult;
import com.example.ragagent.service.ToolDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class A2aRuntime {
    private final LocalA2aAgentTransport localTransport;
    private final RemoteA2aAgentTransport remoteTransport;
    private final A2aTaskStore taskStore;
    private final RagProperties properties;

    public A2aRuntime() {
        this(
                new LocalA2aAgentTransport(),
                null,
                new InMemoryA2aTaskStore(),
                new RagProperties(null, null, null, null, null, null, null, null, null)
        );
    }

    @Autowired
    public A2aRuntime(
            LocalA2aAgentTransport localTransport,
            RemoteA2aAgentTransport remoteTransport,
            A2aTaskStore taskStore,
            RagProperties properties
    ) {
        this.localTransport = localTransport;
        this.remoteTransport = remoteTransport;
        this.taskStore = taskStore;
        this.properties = properties;
    }

    public A2aTaskExecution send(
            SpecialistAgent targetAgent,
            A2aMessage message,
            ChatRequest request,
            QueryAnalysisResponse analysis,
            int startStep
    ) {
        String taskId = message.taskId() == null || message.taskId().isBlank()
                ? "task-" + targetAgent.name() + "-" + UUID.randomUUID()
                : message.taskId();
        A2aMessage taskMessage = new A2aMessage(
                message.role(),
                message.messageId(),
                message.contextId(),
                taskId,
                message.referenceTaskIds(),
                message.parts()
        );

        List<AgentTraceStep> trace = new ArrayList<>();
        taskStore.save(task(taskId, taskMessage.contextId(), A2aTaskState.SUBMITTED, taskMessage, List.of(taskMessage), List.of()));
        taskStore.save(task(taskId, taskMessage.contextId(), A2aTaskState.WORKING, taskMessage, List.of(taskMessage), List.of()));
        trace.add(new AgentTraceStep(
                startStep,
                "a2a_message",
                analysis.route(),
                targetAgent.name(),
                "message_send",
                "taskId=" + taskId + ", to=" + targetAgent.name()
        ));

        try {
            A2aAgentTransport transport = transport(targetAgent.name());
            SpecialistAgentResult agentResult = transport.execute(
                    targetAgent,
                    taskMessage,
                    request,
                    analysis,
                    startStep + 1,
                    timeout()
            );
            trace.addAll(agentResult.trace());

            A2aTask task = agentResult.a2aTask() == null
                    ? completedTask(targetAgent, taskMessage, agentResult)
                    : agentResult.a2aTask();
            taskStore.save(task);
            trace.add(new AgentTraceStep(
                    startStep + agentResult.trace().size() + 1,
                    "a2a_task",
                    analysis.route(),
                    targetAgent.name(),
                    task.status().state().name().toLowerCase(),
                    "taskId=" + task.id() + ", transport=" + transport.name() + ", artifacts=" + task.artifacts().size()
            ));
            return new A2aTaskExecution(task, agentResult.withA2aTask(task), trace);
        } catch (Exception exception) {
            A2aTask failedTask = failedTask(targetAgent, taskMessage, request, exception);
            taskStore.save(failedTask);
            AgentToolResult failure = AgentToolResult.failure(
                    targetAgent.name(),
                    request.query(),
                    exception.getMessage(),
                    "a2a_agent_failed"
            );
            SpecialistAgentResult agentResult = new SpecialistAgentResult(
                    targetAgent.name(),
                    new ToolDecision(true, targetAgent.name(), request.query(), "a2a_agent_failed"),
                    failure,
                    failedTask,
                    List.of(AgentTraceStep.failed(
                            startStep + 1,
                            "agent",
                            analysis.route(),
                            targetAgent.name(),
                            "agent_observation",
                            exception.getMessage(),
                            0,
                            exception
                    ))
            );
            trace.addAll(agentResult.trace());
            trace.add(new AgentTraceStep(
                    startStep + agentResult.trace().size() + 1,
                    "a2a_task",
                    analysis.route(),
                    targetAgent.name(),
                    "failed",
                    "taskId=" + failedTask.id() + ", error=" + exception.getMessage()
            ));
            return new A2aTaskExecution(failedTask, agentResult, trace);
        }
    }

    private A2aAgentTransport transport(String agentName) {
        if (remoteTransport != null && remoteTransport.supports(agentName)) {
            return remoteTransport;
        }
        return localTransport;
    }

    private Duration timeout() {
        int seconds = properties == null || properties.multiAgent() == null
                ? 12
                : properties.multiAgent().timeoutSeconds();
        return Duration.ofSeconds(seconds);
    }

    private A2aTask completedTask(SpecialistAgent targetAgent, A2aMessage taskMessage, SpecialistAgentResult agentResult) {
        A2aArtifact artifact = artifact(targetAgent, agentResult);
        A2aMessage completedMessage = new A2aMessage(
                "agent",
                "msg-" + UUID.randomUUID(),
                taskMessage.contextId(),
                taskMessage.taskId(),
                List.of(taskMessage.taskId()),
                List.of(A2aPart.text(agentResult.toolResult() == null ? "No tool result." : safeObservation(agentResult.toolResult())))
        );
        return task(
                taskMessage.taskId(),
                taskMessage.contextId(),
                completedState(agentResult),
                completedMessage,
                List.of(taskMessage, completedMessage),
                List.of(artifact)
        );
    }

    private A2aTask failedTask(SpecialistAgent targetAgent, A2aMessage taskMessage, ChatRequest request, Exception exception) {
        AgentToolResult failure = AgentToolResult.failure(
                targetAgent.name(),
                request.query(),
                exception.getMessage(),
                "a2a_agent_failed"
        );
        SpecialistAgentResult result = new SpecialistAgentResult(
                targetAgent.name(),
                ToolDecision.none(),
                failure,
                null,
                List.of()
        );
        A2aMessage failedMessage = new A2aMessage(
                "agent",
                "msg-" + UUID.randomUUID(),
                taskMessage.contextId(),
                taskMessage.taskId(),
                List.of(taskMessage.taskId()),
                List.of(A2aPart.text(exception.getMessage()))
        );
        return task(
                taskMessage.taskId(),
                taskMessage.contextId(),
                A2aTaskState.FAILED,
                failedMessage,
                List.of(taskMessage, failedMessage),
                List.of(artifact(targetAgent, result))
        );
    }

    private A2aTask task(
            String taskId,
            String contextId,
            A2aTaskState state,
            A2aMessage message,
            List<A2aMessage> history,
            List<A2aArtifact> artifacts
    ) {
        return new A2aTask(
                taskId,
                contextId,
                new A2aTaskStatus(state, message, Instant.now()),
                history,
                artifacts
        );
    }

    private A2aTaskState completedState(SpecialistAgentResult result) {
        if (result.toolResult() == null) {
            return "follow_up".equals(result.agentName()) ? A2aTaskState.INPUT_REQUIRED : A2aTaskState.COMPLETED;
        }
        return result.toolResult().success() ? A2aTaskState.COMPLETED : A2aTaskState.FAILED;
    }

    private A2aArtifact artifact(SpecialistAgent targetAgent, SpecialistAgentResult result) {
        AgentToolResult toolResult = result.toolResult();
        Map<String, Object> data = Map.of(
                "agentName", safe(result.agentName()),
                "toolName", toolResult == null ? "" : safe(toolResult.toolName()),
                "success", toolResult == null || toolResult.success(),
                "finishReason", toolResult == null || toolResult.finishReason() == null ? "no_tool_required" : toolResult.finishReason(),
                "retrievalHits", result.retrievalHits().size(),
                "webSearchResults", result.webSearchResults().size()
        );
        return new A2aArtifact(
                "artifact-" + targetAgent.name() + "-" + UUID.randomUUID(),
                targetAgent.name() + "-observation",
                "Structured output produced by the " + targetAgent.name() + " specialist agent.",
                List.of(
                        A2aPart.text(toolResult == null ? "No tool result." : safeObservation(toolResult)),
                        A2aPart.data(data)
                )
        );
    }

    private String safeObservation(AgentToolResult result) {
        return result.observation() == null ? "" : result.observation();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
