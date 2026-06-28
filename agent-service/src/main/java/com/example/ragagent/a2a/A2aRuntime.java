package com.example.ragagent.a2a;

import com.example.ragagent.dto.AgentTraceStep;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import com.example.ragagent.service.AgentToolResult;
import com.example.ragagent.service.SpecialistAgent;
import com.example.ragagent.service.SpecialistAgentResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class A2aRuntime {
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
        trace.add(new AgentTraceStep(
                startStep,
                "a2a_message",
                analysis.route(),
                targetAgent.name(),
                "message_send",
                "taskId=" + taskId + ", to=" + targetAgent.name()
        ));

        SpecialistAgentResult agentResult = targetAgent.run(request, analysis, startStep + 1);
        trace.addAll(agentResult.trace());

        A2aArtifact artifact = artifact(targetAgent, agentResult);
        A2aMessage completedMessage = new A2aMessage(
                "agent",
                "msg-" + UUID.randomUUID(),
                taskMessage.contextId(),
                taskId,
                List.of(taskId),
                List.of(A2aPart.text(agentResult.toolResult() == null ? "No tool result." : safeObservation(agentResult.toolResult())))
        );
        A2aTaskState state = completedState(agentResult);
        A2aTask task = new A2aTask(
                taskId,
                taskMessage.contextId(),
                new A2aTaskStatus(state, completedMessage, Instant.now()),
                List.of(taskMessage, completedMessage),
                List.of(artifact)
        );
        trace.add(new AgentTraceStep(
                startStep + agentResult.trace().size() + 1,
                "a2a_task",
                analysis.route(),
                targetAgent.name(),
                state.name().toLowerCase(),
                "taskId=" + taskId + ", artifacts=" + task.artifacts().size()
        ));
        return new A2aTaskExecution(task, agentResult.withA2aTask(task), trace);
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
