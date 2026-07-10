package com.example.ragagent.a2a;

import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.service.ChatOrchestrator;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.UnsupportedOperationError;
import java.util.List;

public class RagA2aAgentExecutor implements AgentExecutor {
    private final ChatOrchestrator chatOrchestrator;

    public RagA2aAgentExecutor(ChatOrchestrator chatOrchestrator) {
        this.chatOrchestrator = chatOrchestrator;
    }

    @Override
    public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        try {
            Message message = context.getMessage();
            String query = A2aSdkConverter.text(message);
            if (query.isBlank()) {
                throw new UnsupportedOperationError(-32602, "A2A message text part must not be blank", null);
            }

            A2aTask task = chatOrchestrator.answerTask(new ChatRequest(
                    query,
                    A2aSdkConverter.stringMetadata(message, "knowledgeBaseId"),
                    context.getContextId(),
                    List.of(),
                    null
            ));
            eventQueue.enqueueEvent(A2aSdkConverter.toSdkTask(task, context.getTaskId(), context.getContextId()));
        } finally {
            eventQueue.taskDone();
        }
    }

    @Override
    public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        try {
            Task task = context.getTask();
            if (task == null) {
                throw new UnsupportedOperationError(-32004, "A2A task not found: " + context.getTaskId(), null);
            }
            eventQueue.enqueueEvent(new Task.Builder(task)
                    .status(new io.a2a.spec.TaskStatus(io.a2a.spec.TaskState.CANCELED))
                    .build());
        } finally {
            eventQueue.taskDone();
        }
    }
}
